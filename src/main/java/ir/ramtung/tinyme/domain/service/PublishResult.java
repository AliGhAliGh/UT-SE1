package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@NoArgsConstructor
public class PublishResult {
    MatchResult matchResult;
    long reqId;
    long orderId;
    OrderEntryType type;
    Security security;
    EventPublisher eventPublisher;

    public PublishResult(MatchResult matchResult, long reqId, long orderId, OrderEntryType type,
            Security security, EventPublisher eventPublisher) {
        this.matchResult = matchResult;
        this.eventPublisher = eventPublisher;
        this.orderId = orderId;
        this.reqId = reqId;
        this.security = security;
        this.type = type;
    }

    public void publishResult() {
        if (type == OrderEntryType.ACTIVATED)
            eventPublisher.publish(new OrderActivatedEvent(reqId, orderId));

        switch (matchResult.outcome()) {
            case NOT_ENOUGH_CREDIT:
                eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                        List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
                break;
            case NOT_ENOUGH_POSITIONS:
                eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                        List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
                break;
            case NOT_SATISFY_MEQ:
                eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                        List.of(Message.ORDER_NOT_SATISFIED_MEQ)));
                break;
            case CHANGE_OPENING_PRICE:
                var openingPrice = security.getOpeningPrice();
                var tradedQuantity = security.tradedQuantityAtPrice(openingPrice);
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), openingPrice, tradedQuantity));
            default:
                if (type == OrderEntryType.NEW_ORDER)
                    eventPublisher.publish(new OrderAcceptedEvent(reqId, orderId));
                else if (type == OrderEntryType.UPDATE_ORDER)
                    eventPublisher.publish(new OrderUpdatedEvent(reqId, orderId));
                if (!matchResult.trades().isEmpty())
                    eventPublisher.publish(new OrderExecutedEvent(reqId, orderId,
                            matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
                break;
        }
    }
}
