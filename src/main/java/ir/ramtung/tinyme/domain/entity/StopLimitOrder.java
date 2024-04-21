package ir.ramtung.tinyme.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

import java.time.LocalDateTime;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {
    private int stopPrice;
    private boolean active;
    private long requestId;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker,
            Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice, long requestId) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
        this.active = true;
        this.requestId = requestId;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker,
            Shareholder shareholder, LocalDateTime entryTime, int stopPrice, long requestId) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.NEW, stopPrice,
                requestId);
        this.active = true;
    }

    @Override
    public Order snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime,
                OrderStatus.SNAPSHOT, stopPrice, requestId);
    }

    @Override
    public boolean queuesBefore(Order order) {
        if (active)
            return super.queuesBefore(order);
        else {
            var sl = (StopLimitOrder) order;
            if (sl.getSide() == Side.BUY)
                return stopPrice < sl.getStopPrice();
            else
                return stopPrice > sl.getStopPrice();
        }
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new StopLimitOrder(orderId, security, side, newQuantity, price, broker, shareholder, entryTime,
                OrderStatus.SNAPSHOT, stopPrice, requestId);
    }

    public boolean checkActivation(int lastTradePrice) {
        return (this.side == Side.BUY && this.stopPrice <= lastTradePrice)
                || (this.side == Side.SELL && this.stopPrice >= lastTradePrice);
    }

    public void activate() {
        if (side == BUY)
            broker.increaseCreditBy(getValue());
        active = true;
    }

    public void deactivate() {
        if (side == BUY)
            broker.decreaseCreditBy(getValue());
        active = false;
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        if (updateOrderRq.getStopPrice() != 0)
            stopPrice = updateOrderRq.getStopPrice();
    }
}
