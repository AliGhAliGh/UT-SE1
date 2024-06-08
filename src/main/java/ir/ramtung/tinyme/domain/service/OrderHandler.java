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

    private void refreshQueue(Security security) {
        var res = security.getOrderBook().refreshAllQueue(security);
        while (!res.isEmpty()) {
            for (var pair : res) {
                var order = (StopLimitOrder) pair.getA();
                PublishResult publishResult = new PublishResult(pair.getB(), order.getRequestId(), order.getOrderId(), OrderEntryType.ACTIVATED,
                        security,eventPublisher);
                publishResult.publishResult();
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

            PublishResult publishResult = new PublishResult(matchResult, enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    enterOrderRq.getRequestType(), security, eventPublisher);
            publishResult.publishResult();

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
