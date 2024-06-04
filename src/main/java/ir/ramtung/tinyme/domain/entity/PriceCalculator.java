package ir.ramtung.tinyme.domain.entity;

import java.security.InvalidAlgorithmParameterException;
import java.util.Iterator;
import java.util.LinkedList;

public class PriceCalculator {
    int lastPrice;
    Iterator<Order> sellIt;
    Iterator<Order> buyIt;
    int maxTradedQuantity = 0, delta = 0, openingPrice = 0, endPrice;
    boolean isLastPriceChecked = false;
    Order sell;
    Order buy;
    LinkedList<Order> buyQueue, sellQueue;

    public PriceCalculator(OrderBook orderBook, int lastPriceExecuted) throws InvalidAlgorithmParameterException {
        buyQueue = orderBook.getBuyQueue();
        sellQueue = orderBook.getSellQueue();
        if (!isValid())
            throw new InvalidAlgorithmParameterException();
        lastPrice = lastPriceExecuted;
        sellIt = sellQueue.descendingIterator();
        buyIt = buyQueue.iterator();
        endPrice = sellQueue.getFirst().getPrice();
        sell = sellIt.next();
        buy = buyIt.next();
    }

    private boolean isValid() {
        return !(buyQueue.isEmpty() || sellQueue.isEmpty()
                || buyQueue.getFirst().getPrice() < sellQueue.getFirst().getPrice());
    }

    public int tradedQuantityAtPrice(int price) {
        var buys = buyQueue.stream().filter(c -> c.getPrice() >= price)
                .mapToInt(Order::getQuantity).sum();
        var sells = sellQueue.stream().filter(c -> c.getPrice() <= price)
                .mapToInt(Order::getQuantity).sum();
        return Math.min(sells, buys);
    }

    public int getOpeningPrice() {
        while (sell.getPrice() > buy.getPrice())
            sell = sellIt.next();
        while (true) {
            var currentPrice = CalculateCurrentPrice();
            if (currentPrice < endPrice)
                break;
            MoveNext(currentPrice);
            if (!checkQueues(currentPrice))
                break;
        }
        return openingPrice;
    }

    private int CalculateCurrentPrice() {
        var currentPrice = Math.max(sell.getPrice(), buy == null ? 0 : buy.getPrice());
        if (!isLastPriceChecked && lastPrice >= currentPrice) {
            currentPrice = lastPrice;
            isLastPriceChecked = true;
        }
        return currentPrice;
    }

    private void MoveNext(int currentPrice) {
        var currentTradedQuantity = tradedQuantityAtPrice(currentPrice);
        if (currentTradedQuantity > maxTradedQuantity) {
            maxTradedQuantity = currentTradedQuantity;
            delta = Math.abs(lastPrice - currentPrice);
            openingPrice = currentPrice;
        } else if (currentTradedQuantity == maxTradedQuantity) {
            if (Math.abs(lastPrice - currentPrice) < delta) {
                openingPrice = currentPrice;
                delta = Math.abs(lastPrice - currentPrice);
            } else if (Math.abs(lastPrice - currentPrice) == delta) {
                openingPrice = Math.min(openingPrice, currentPrice);
            }
        }
    }

    private boolean checkQueues(int currentPrice) {
        if (currentPrice == sell.getPrice()) {
            if (!sellIt.hasNext())
                return false;
            sell = sellIt.next();
        } else if (buy != null && currentPrice == buy.getPrice()) {
            if (buyIt.hasNext())
                buy = buyIt.next();
            else
                buy = null;
        }
        return true;
    }
}
