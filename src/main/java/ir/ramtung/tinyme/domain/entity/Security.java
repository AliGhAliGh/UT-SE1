package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.Controller;
import ir.ramtung.tinyme.domain.service.Validator;
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
import lombok.Setter;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

import java.security.InvalidAlgorithmParameterException;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class Security {
    @Getter
    @Setter
    @Builder.Default
    private int lastPriceExecuted = 0;
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
        if (!Controller.checkPosition(enterOrderRq, shareholder, this))
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
        if (order instanceof StopLimitOrder sl && !sl.checkActivation(lastPriceExecuted)) {
            if (!Controller.checkCredit(order))
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

        Validator.validateUpdateOrder(order, updateOrderRq);

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
        try {
            return new PriceCalculator(orderBook, lastPriceExecuted).tradedQuantityAtPrice(price);
        } catch (InvalidAlgorithmParameterException err) {
            return 0;
        }
    }

    public int getOpeningPrice() {
        try {
            return new PriceCalculator(orderBook, lastPriceExecuted).getOpeningPrice();
        } catch (InvalidAlgorithmParameterException err) {
            return 0;
        }
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
