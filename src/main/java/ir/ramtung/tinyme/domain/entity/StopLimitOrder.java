package ir.ramtung.tinyme.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {
    private final int stopPrice;
    private boolean active;
    private long requestId;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker,
            Shareholder shareholder, LocalDateTime entryTime, OrderStatus status, int stopPrice, long requestId) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
        this.active = false;
        this.requestId = requestId;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker,
            Shareholder shareholder, LocalDateTime entryTime, int stopPrice, long requestId) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.NEW, stopPrice,
                requestId);
        this.active = false;
    }

    @Override
    public Order snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime,
                OrderStatus.SNAPSHOT, stopPrice, requestId);
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
        System.out.println("activated : " + orderId);
        active = true;
    }

    public void deactivate() {
        System.out.println("deactivated : " + orderId);
        active = false;
    }
}
