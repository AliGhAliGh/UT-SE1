package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    public static MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        Order matchingOrder;
        while ((matchingOrder = orderBook.tryMatchWithFirst(newOrder)) != null && newOrder.getQuantity() > 0) {
            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(),
                    Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY && !trade.tryDecreaseBuyer()) {
                rollbackTrades(newOrder, trades);
                return MatchResult.notEnoughCredit();
            }

            trade.increaseSellerCredit();
            trades.add(trade);
            decreasePop(orderBook, matchingOrder, trade.getQuantity());
            newOrder.decreaseQuantity(trade.getQuantity());
        }
        return MatchResult.executed(newOrder, trades);
    }

    private static void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        var tradedValue = trades.stream().mapToLong(Trade::getTradedValue).sum();
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(tradedValue);
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));
            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
            }
        } else {
            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                var trade = it.previous();
                trade.decreaseSellerCredit();
                newOrder.getSecurity().getOrderBook().restoreOrder(trade.getBuy());
            }
        }
    }

    public static MatchResult execute(Order order, int minimumExecutionQuantity) {
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            return result;

        if (result.remainder().getQuantity() > 0) {
            var tradedQuantity = result.trades().stream().mapToLong(Trade::getQuantity).sum();
            if (tradedQuantity < minimumExecutionQuantity) {
                rollbackTrades(order, result.trades());
                return MatchResult.notMEQTrade();
            }
            if (order.getSide() == Side.BUY && !order.getBroker().tryDecreaase(order)) {
                rollbackTrades(order, result.trades());
                return MatchResult.notEnoughCredit();
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        exchangePositions(result.trades());
        return result;
    }

    private static void exchangePositions(LinkedList<Trade> trades) {
        if (trades.isEmpty())
            return;
        for (Trade trade : trades) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSecurity().setLastPriceExecuted(trade.getPrice());
        }
    }

    private static void decreasePop(OrderBook orderBook, Order order, int quantity) {
        order.decreaseQuantity(quantity);
        if (order.getQuantity() == 0) {
            orderBook.removeFirst(order.getSide());
            if (order instanceof IcebergOrder icebergOrder && icebergOrder.tryReplenish())
                order.getSecurity().getOrderBook().enqueue(icebergOrder);
        }
    }

    public static LinkedList<Trade> executeAuction(Security security) {
        int openingPrice = security.getOpeningPrice(), tradableQuanitity;
        OrderBook orderBook = security.getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        Order buyOrder, sellOrder;
        while ((buyOrder = orderBook.findMatchAuction(Side.BUY, openingPrice)) != null
                && (sellOrder = orderBook.findMatchAuction(Side.SELL, openingPrice)) != null) {
            tradableQuanitity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            Trade trade = new Trade(security, openingPrice, tradableQuanitity, buyOrder, sellOrder);
            decreasePop(orderBook, buyOrder, tradableQuanitity);
            decreasePop(orderBook, sellOrder, tradableQuanitity);
            trade.increaseBuyerDiffCredit();
            trade.increaseSellerCredit();
            trades.add(trade);
        }

        exchangePositions(trades);
        return trades;
    }
}
