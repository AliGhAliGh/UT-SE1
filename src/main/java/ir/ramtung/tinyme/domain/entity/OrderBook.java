package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import static ir.ramtung.tinyme.domain.entity.Side.SELL;

import java.util.LinkedList;
import java.util.ListIterator;

import org.apache.activemq.artemis.api.core.Pair;

import ir.ramtung.tinyme.domain.service.Matcher;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private final LinkedList<Order> deactivatedQueueBuy;
    private final LinkedList<Order> deactivatedQueueSell;

    public OrderBook() {
        deactivatedQueueBuy = new LinkedList<>();
        deactivatedQueueSell = new LinkedList<>();
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        addToQueue(getQueue(order.getSide()), order);
        order.queue();
    }

    private void addToQueue(LinkedList<Order> queue, Order order) {
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        it.add(order);
    }

    public void enqueueDeactivated(StopLimitOrder order) {
        var queue = getDeactivatedQueue(order.getSide());
        order.deactivate();
        addToQueue(queue, order);
    }

    private LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }
    private LinkedList<Order> getDeactivatedQueue(Side side) {
        return side == Side.BUY ? deactivatedQueueBuy : deactivatedQueueSell;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        queue = getDeactivatedQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeActiveOrder(Side side, long orderId) {
        var queue = getQueue(side);
        return deleteInQueue(queue, orderId);
    }

    private boolean deleteInQueue(LinkedList<Order> queue, long orderId) {
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public boolean removeOrder(Order order) {
        var queue = order instanceof StopLimitOrder ? getDeactivatedQueue(order.getSide()) : getQueue(order.getSide());
        return deleteInQueue(queue, order.getOrderId());
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreOrder(Order order) {
        removeActiveOrder(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum() +
                deactivatedQueue.stream()
                        .filter(order -> order.getSide() == SELL && order.getShareholder().equals(shareholder))
                        .mapToInt(Order::getTotalQuantity)
                        .sum();
    }

    public LinkedList<Pair<Order, MatchResult>> refreshAllQueue(Matcher matcher) {
        var it = deactivatedQueueBuy.listIterator();
        var res = new LinkedList<Pair<Order, MatchResult>>();
        while (it.hasNext()) {
            var order = (StopLimitOrder) it.next();
            if (order.checkActivation(matcher.getLastPriceExecuted())) {
                it.remove();
                order.activate();
                res.add(new Pair<Order, MatchResult>(order, matcher.execute(order, 0)));
            }
        }

        it = deactivatedQueueSell.listIterator();

        while (it.hasNext()) {
            var order = (StopLimitOrder) it.next();
            if (order.checkActivation(matcher.getLastPriceExecuted())) {
                it.remove();
                order.activate();
                res.add(new Pair<Order, MatchResult>(order, matcher.execute(order, 0)));
            }
        }

        return res;
    }
}
