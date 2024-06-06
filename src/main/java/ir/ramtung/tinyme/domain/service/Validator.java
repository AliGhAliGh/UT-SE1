package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;

@Service
public class Validator {
    public static void validateUpdateOrder(Order order, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order instanceof StopLimitOrder sl && sl.isActive() && updateOrderRq.getStopPrice() != sl.getStopPrice() &&
                updateOrderRq.getStopPrice() > 0)
            throw new InvalidRequestException(Message.ACTIVE_ORDER_STOP_LIMIT_UPDATE);
    }

    public static void validateEnterOrderRq(EnterOrderRq enterOrderRq, SecurityRepository securityRepository,
            BrokerRepository brokerRepository, ShareholderRepository shareholderRepository)
            throws InvalidRequestException {
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
            if (security.getState() == MatchingState.AUCTION) {
                if (enterOrderRq.getMinimumExecutionQuantity() > 0)
                    errors.add(Message.MEQ_IN_AUCTION_STATE);
                if (enterOrderRq.getStopPrice() > 0)
                    errors.add(Message.STOP_LIMIT_ORDER_IN_AUCTION_STATE);
                if (security.getOrderBook().isInactiveStopLimitOrder(enterOrderRq.getSide(), enterOrderRq.getOrderId()))
                    errors.add(Message.UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_STATE);
            }
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

    public static void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq, SecurityRepository securityRepository)
            throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);

        Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else if (security.getOrderBook().isInactiveStopLimitOrder(deleteOrderRq.getSide(), deleteOrderRq.getOrderId())
                && security.getState() == MatchingState.AUCTION)
            errors.add(Message.DELETE_STOP_LIMIT_ORDER_IN_AUCTION_STATE);

        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
