package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
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
    Matcher matcher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository,
            ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
    }

    private void publishResult(MatchResult matchResult, long reqId, long orderId, OrderEntryType type) {
        if (type == OrderEntryType.ACTIVATED)
            eventPublisher.publish(new OrderActivatedEvent(reqId, orderId));
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                    List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                    List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_SATISFY_MEQ) {
            eventPublisher.publish(new OrderRejectedEvent(reqId, orderId,
                    List.of(Message.ORDER_NOT_SATISFIED_MEQ)));
            return;
        }
        if (type == OrderEntryType.NEW_ORDER)
            eventPublisher.publish(new OrderAcceptedEvent(reqId, orderId));
        else if (type == OrderEntryType.UPDATE_ORDER)
            eventPublisher.publish(new OrderUpdatedEvent(reqId, orderId));
        if (!matchResult.trades().isEmpty())
            eventPublisher.publish(new OrderExecutedEvent(reqId, orderId,
                    matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
    }

    private void refreshQueue(Security security) {
        var res = security.getOrderBook().refreshAllQueue(matcher);
        while (!res.isEmpty()) {
            for (var pair : res) {
                var order = (StopLimitOrder) pair.getA();
                publishResult(pair.getB(), order.getRequestId(), order.getOrderId(), OrderEntryType.ACTIVATED);
            }
            res = security.getOrderBook().refreshAllQueue(matcher);
        }
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            System.out.println("BROKER : " + broker.getCredit());

            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            else
                matchResult = security.updateOrder(enterOrderRq, matcher);

            System.out.println("BROKER : " + broker.getCredit());

            publishResult(matchResult, enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    enterOrderRq.getRequestType());
            refreshQueue(security);

        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        if (enterOrderRq.getMinimumExecutionQuantity() < 0)
            errors.add(Message.ORDER_MEQ_NOT_POSITIVE);
        if (enterOrderRq.getQuantity() < enterOrderRq.getMinimumExecutionQuantity())
            errors.add(Message.ORDER_QUANTITY_SMALLER_THAN_MEQ);
        if (enterOrderRq.getPeakSize() > 0 && enterOrderRq.getStopPrice() > 0)
            errors.add(Message.ICEBERG_WITH_STOP_LIMIT_ORDER);
        if (enterOrderRq.getMinimumExecutionQuantity() > 0 && enterOrderRq.getStopPrice() > 0)
            errors.add(Message.MEQ_WITH_STOP_LIMIT_ORDER);

        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }

        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
