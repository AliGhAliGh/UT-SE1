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

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(),
                    Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyerCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }

            trade.increaseSellerCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                refreshIcebergOrder(matchingOrder);
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
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
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        exchangePositions(result.trades());
        return result;
    }

    private static void exchangePositions(LinkedList<Trade> trades) {
        if (!trades.isEmpty()) {
            for (Trade trade : trades) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSecurity().setLastPriceExecuted(trade.getPrice());
            }
        }
    }

    private static void refreshIcebergOrder(Order orderbook) {
        if (orderbook instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(orderbook.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderbook.getSecurity().getOrderBook().enqueue(icebergOrder);
        }
    }

    public static LinkedList<Trade> executeAuction(Security security) {
        int openingPrice = security.getOpeningPrice();
        OrderBook orderBook = security.getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        int tradableQuanitity;

        Order buyOrder = orderBook.findMatchAuction(Side.BUY, openingPrice);
        Order sellOrder = orderBook.findMatchAuction(Side.SELL, openingPrice);

        while (buyOrder != null && sellOrder != null) {
            tradableQuanitity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            Trade trade = new Trade(security, openingPrice, tradableQuanitity, buyOrder, sellOrder);

            if (buyOrder.getQuantity() > tradableQuanitity)
                buyOrder.decreaseQuantity(tradableQuanitity);
            else {
                orderBook.removeFirst(buyOrder.getSide());
                refreshIcebergOrder(buyOrder);
            }

            if (sellOrder.getQuantity() > tradableQuanitity)
                sellOrder.decreaseQuantity(tradableQuanitity);
            else {
                orderBook.removeFirst(sellOrder.getSide());
                refreshIcebergOrder(sellOrder);
            }

            trade.increaseBuyerDiffCredit();
            trade.increaseSellerCredit();
            trades.add(trade);

            buyOrder = orderBook.findMatchAuction(Side.BUY, openingPrice);
            sellOrder = orderBook.findMatchAuction(Side.SELL, openingPrice);
        }

        exchangePositions(trades);
        return trades;
    }
}
