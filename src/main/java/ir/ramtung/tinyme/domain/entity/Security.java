package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

import java.util.List;

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
    private MatchingState state = MatchingState.CONTINUOUS;
    private int openingPrice;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
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

        if (order instanceof StopLimitOrder sl && !sl.checkActivation(matcher.getLastPriceExecuted())) {
            if (order.getSide() == BUY && !order.getBroker().hasEnoughCredit(order.getValue()))
                return MatchResult.notEnoughCredit();
            sl.deactivate();
            orderBook.enqueueDeactivated(sl);
            return MatchResult.deactivated();
        }

        if (state == MatchingState.AUCTION) {
            if (order.getSide() == BUY) {
                if (!matcher.buyOrderEnterAuction(order.getBroker(), order)) {
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }

            orderBook.enqueue(order);
            return matcher.executeAuction(this, openingPrice);
        }

        return matcher.execute(order, enterOrderRq.getMinimumExecutionQuantity());
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeOrder(order);
    }

    private MatchResult updateNormalOrder(EnterOrderRq updateOrderRq, Matcher matcher, Order order) {
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
        MatchResult matchResult = matcher.execute(order, updateOrderRq.getMinimumExecutionQuantity());
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    private MatchResult updateSlOrder(EnterOrderRq updateOrderRq, StopLimitOrder order) {
        boolean losesPriority = updateOrderRq.getStopPrice() != order.getStopPrice();
        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
            if (!order.getBroker().hasEnoughCredit(updateOrderRq.getQuantity() * updateOrderRq.getPrice())) {
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

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
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
            return updateNormalOrder(updateOrderRq, matcher, order);
    }

    public void changeState(MatchingState state) {
        this.state = state;
    }
}
