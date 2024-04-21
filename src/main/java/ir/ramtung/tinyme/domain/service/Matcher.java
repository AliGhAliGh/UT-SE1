package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import lombok.Getter;
import lombok.Setter;

import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
@Getter
public class Matcher {
    @Setter
    private int lastPriceExecuted = 0;

    public MatchResult match(Order newOrder) {
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
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }

            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());

                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        return MatchResult.executed(newOrder, trades);
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
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
                trade.decreaseSellersCredit();
                newOrder.getSecurity().getOrderBook().restoreOrder(trade.getBuy());
            }
        }
    }

    public MatchResult execute(Order order, int minimumExecutionQuantity) {
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
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
            lastPriceExecuted = result.trades().getLast().getPrice();
        }
        return result;
    }
}
