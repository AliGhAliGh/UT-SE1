package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.Event;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.event.TradeEvent;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import static ir.ramtung.tinyme.domain.entity.Side.BUY;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private MatchingState state = MatchingState.CONTINUOUS;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getSide() == Side.SELL && !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        Order order;
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime());
        else if (enterOrderRq.getStopPrice() > 0)
            order = new StopLimitOrder(
                    enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getStopPrice(), enterOrderRq.getRequestId());
        else
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize());

        return handleEnterOrder(order, enterOrderRq.getMinimumExecutionQuantity());
    }

    public MatchResult handleEnterOrder(Order order, int meq) {
        if (order instanceof StopLimitOrder sl && !sl.checkActivation(Matcher.getLastPriceExecuted())) {
            if (order.getSide() == BUY && !order.getBroker().hasEnoughCredit(order.getValue()))
                return MatchResult.notEnoughCredit();
            sl.deactivate();
            orderBook.enqueueDeactivated(sl);
            return MatchResult.deactivated();
        }

        if (state == MatchingState.AUCTION) {
            if (order.getSide() == BUY && !order.getBroker().tryDecreaase(order)) {
                return MatchResult.notEnoughCredit();
            }

            orderBook.enqueue(order);
            return MatchResult.orderChangedOpeningPrice();
        }

        return Matcher.execute(order, meq);
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeOrder(order);
    }

    private MatchResult updateNormalOrder(EnterOrderRq updateOrderRq, Order order) {
        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder)
                        && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        } else
            order.markAsNew();

        orderBook.removeActiveOrder(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult;
        if (this.state == MatchingState.CONTINUOUS) {
            matchResult = Matcher.execute(order, updateOrderRq.getMinimumExecutionQuantity());
            if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
                orderBook.enqueue(originalOrder);
                if (updateOrderRq.getSide() == Side.BUY)
                    originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        } else {
            orderBook.enqueue(order);
            matchResult = MatchResult.orderChangedOpeningPrice();
        }

        return matchResult;
    }

    private MatchResult updateSlOrder(EnterOrderRq updateOrderRq, StopLimitOrder order) {
        boolean losesPriority = updateOrderRq.getStopPrice() != order.getStopPrice();
        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
            if (!order.getBroker().hasEnoughCredit((long) updateOrderRq.getQuantity() * updateOrderRq.getPrice())) {
                order.getBroker().decreaseCreditBy(order.getValue());
                return MatchResult.notEnoughCredit();
            }
        }

        order.updateFromRequest(updateOrderRq);
        if (updateOrderRq.getSide() == Side.BUY)
            order.getBroker().decreaseCreditBy(order.getValue());

        if (losesPriority) {
            orderBook.removeOrder(order);
            orderBook.enqueueDeactivated(order);
        }
        return MatchResult.executed(null, List.of());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order instanceof StopLimitOrder sl && sl.isActive() && updateOrderRq.getStopPrice() != sl.getStopPrice() &&
                updateOrderRq.getStopPrice() > 0)
            throw new InvalidRequestException(Message.ACTIVE_ORDER_STOP_LIMIT_UPDATE);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity()
                                + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        if (order instanceof StopLimitOrder sl && !sl.isActive())
            return updateSlOrder(updateOrderRq, sl);
        else
            return updateNormalOrder(updateOrderRq, order);
    }

    public int tradedQuantityAtPrice(int price) {
        var buys = orderBook.getBuyQueue().stream().filter(c -> c.getPrice() >= price)
                .mapToInt(Order::getQuantity).sum();
        var sells = orderBook.getSellQueue().stream().filter(c -> c.getPrice() <= price)
                .mapToInt(Order::getQuantity).sum();
        return Math.min(sells, buys);
    }

    public int getOpeningPrice() {
        int lastPrice = Matcher.getLastPriceExecuted();
        if (orderBook.getBuyQueue().isEmpty() || orderBook.getSellQueue().isEmpty()
                || orderBook.getBuyQueue().getFirst().getPrice() < orderBook.getSellQueue().getFirst().getPrice())
            return 0;

        var sellIt = orderBook.getSellQueue().descendingIterator();
        var buyIt = orderBook.getBuyQueue().iterator();
        int maxTradedQuantity = 0, delta = 0, openingPrice = 0,
                endPrice = orderBook.getSellQueue().getFirst().getPrice();
        boolean isLastPriceChecked = false;
        Order sell = sellIt.next();
        Order buy = buyIt.next();
        while (sell.getPrice() > buy.getPrice())
            sell = sellIt.next();
        while (true) {
            var currentPrice = Math.max(sell.getPrice(), buy == null ? 0 : buy.getPrice());
            if (!isLastPriceChecked && lastPrice >= currentPrice) {
                currentPrice = lastPrice;
                isLastPriceChecked = true;
            }
            if (currentPrice < endPrice)
                break;
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
            if (currentPrice == sell.getPrice()) {
                if (!sellIt.hasNext())
                    break;
                sell = sellIt.next();
            } else if (buy != null && currentPrice == buy.getPrice()) {
                if (buyIt.hasNext())
                    buy = buyIt.next();
                else
                    buy = null;
            }
        }
        return openingPrice;
    }

    private Event getEvent(Trade trade) {
        return (Event) new TradeEvent(isin, trade.getPrice(), trade.getQuantity(), trade.getBuy().getOrderId(),
                trade.getSell().getOrderId());
    }

    public List<Event> changeState(MatchingState state) {
        List<Event> res = new LinkedList<Event>();
        res.add(new SecurityStateChangedEvent(isin, state));

        if (this.state == MatchingState.AUCTION) {
            var events = Matcher.executeAuction(this);
            res.addAll(events.stream().map(this::getEvent).collect(Collectors.toList()));
        }
        this.state = state;
        return res;
    }
}
