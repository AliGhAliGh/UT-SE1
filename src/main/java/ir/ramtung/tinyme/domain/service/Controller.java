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

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

@Service
public class Controller {
    public static boolean checkPosition(EnterOrderRq enterOrderRq, Shareholder shareholder, Security security) {
        if (enterOrderRq.getSide() == Side.SELL && !shareholder.hasEnoughPositionsOn(security,
                security.getOrderBook().totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return false;
        return true;
    }

    public static boolean checkCredit(Order order) {
        if (order.getSide() == BUY && !order.getBroker().hasEnoughCredit(order.getValue()))
            return false;
        return true;
    }
}
