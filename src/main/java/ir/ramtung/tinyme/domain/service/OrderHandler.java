package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository,
            ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
    }

    private void publishResult(MatchResult matchResult, long reqId, long orderId, OrderEntryType type,
            Security security) {
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

    private void refreshQueue(Security security) {
        var res = security.getOrderBook().refreshAllQueue(security);
        while (!res.isEmpty()) {
            for (var pair : res) {
                var order = (StopLimitOrder) pair.getA();
                publishResult(pair.getB(), order.getRequestId(), order.getOrderId(), OrderEntryType.ACTIVATED,
                        security);
            }
            res = security.getOrderBook().refreshAllQueue(security);
        }
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            Validator.validateEnterOrderRq(enterOrderRq, securityRepository, brokerRepository, shareholderRepository);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder);
            else
                matchResult = security.updateOrder(enterOrderRq);

            publishResult(matchResult, enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    enterOrderRq.getRequestType(), security);
            if (!(security.getState() == MatchingState.AUCTION
                    && enterOrderRq.getRequestType() == OrderEntryType.UPDATE_ORDER))
                refreshQueue(security);

        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            Validator.validateDeleteOrderRq(deleteOrderRq, securityRepository);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            if (security.getState() == MatchingState.AUCTION)
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), security.getOpeningPrice(),
                        security.tradedQuantityAtPrice(security.getOpeningPrice())));
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleChangeState(ChangeMatchingStateRq changeMatchingStateRq) {
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        if (security.getState() == MatchingState.CONTINUOUS
                && changeMatchingStateRq.getTargetState() == MatchingState.CONTINUOUS)
            return;
        var events = security.changeState(changeMatchingStateRq.getTargetState());
        events.forEach(event -> eventPublisher.publish(event));
    }
}
