package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionTest {
    private Security security;
    private Broker brokerSell, brokerBuy;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        brokerSell = Broker.builder().brokerId(2).credit(100_000_000L).build();
        brokerBuy = Broker.builder().brokerId(1).credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);

        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();
        securityRepository.addSecurity(security);
        shareholderRepository.addShareholder(shareholder);
        brokerRepository.addBroker(brokerBuy);
        brokerRepository.addBroker(brokerSell);

        Matcher.setLastPriceExecuted(15700);

        orderBook = security.getOrderBook();
    }

    @Test
    public void opening_price_test() {
        orders = Arrays.asList(
                new Order(6, security, SELL, 200, 15800, brokerSell, shareholder),
                new Order(7, security, SELL, 200, 15810, brokerSell, shareholder),
                new Order(6, security, Side.BUY, 200, 15900, brokerBuy, shareholder),
                new Order(7, security, Side.BUY, 200, 15910, brokerBuy, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        Matcher.setLastPriceExecuted(15850);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15850);
        Matcher.setLastPriceExecuted(15950);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15900);
        Matcher.setLastPriceExecuted(15750);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15810);
    }

    @Test
    public void opening_price_in_all_match_test() {
        orders = Arrays.asList(
                new Order(6, security, SELL, 200, 15800, brokerSell, shareholder),
                new Order(7, security, Side.BUY, 1, 15900, brokerBuy, shareholder),
                new Order(8, security, Side.BUY, 200, 15910, brokerBuy, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        Matcher.setLastPriceExecuted(15920);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15910);
    }

    @Test
    public void more_quantity_preferred_over_being_close_to_last_price() {
        orders = Arrays.asList(
                new Order(6, security, SELL, 200, 15700, brokerSell, shareholder),
                new Order(1, security, SELL, 300, 15800, brokerSell, shareholder),
                new Order(7, security, Side.BUY, 200, 15900, brokerBuy, shareholder),
                new Order(8, security, Side.BUY, 300, 15800, brokerBuy, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        Matcher.setLastPriceExecuted(15700);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15800);
    }

    @Test
    public void checking_several_number_of_trades_quantity() {
        orders = Arrays.asList(
                new Order(6, security, SELL, 200, 15600, brokerSell, shareholder),
                new Order(6, security, SELL, 200, 15700, brokerSell, shareholder),
                new Order(1, security, SELL, 300, 15800, brokerSell, shareholder),
                new Order(1, security, SELL, 300, 15900, brokerSell, shareholder),
                new Order(7, security, Side.BUY, 200, 15900, brokerBuy, shareholder),
                new Order(7, security, Side.BUY, 200, 15700, brokerBuy, shareholder),
                new Order(8, security, Side.BUY, 200, 15600, brokerBuy, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        Matcher.setLastPriceExecuted(15800);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15700);
    }

    @Test
    public void order_will_not_trade_when_the_state_is_auction() {
        orders = Arrays.asList(
                new Order(8, security, SELL, 10, 15700, brokerSell, shareholder),
                new Order(6, security, SELL, 10, 15800, brokerSell, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 12, LocalDateTime.now(), BUY, 10, 15700,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);
        var req2 = new ChangeMatchingStateRq(security.getIsin(), MatchingState.AUCTION);
        orderHandler.handleChangeState(req2);
        req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 14, LocalDateTime.now(), BUY, 10, 15900,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);
        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15800);
        assertThat(orderBook.getBuyQueue().size()).isEqualTo(1);
    }


    @Test //------------------
    public void check_broker_buy_credit() {
        Matcher.setLastPriceExecuted(15700);

        var req2 = new ChangeMatchingStateRq(security.getIsin(),
                MatchingState.AUCTION);
        orderHandler.handleChangeState(req2);

        var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), BUY, 100, 15900,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);

        req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12,
                LocalDateTime.now(), BUY, 100, 15800,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);

        req = EnterOrderRq.createNewOrderRq(4, security.getIsin(), 14,
                LocalDateTime.now(), SELL, 150, 15600,
                brokerSell.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);

        req2 = new ChangeMatchingStateRq(security.getIsin(),
                MatchingState.CONTINUOUS);
        orderHandler.handleChangeState(req2);

        assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15700);
        assertThat(brokerBuy.getCredit()).isEqualTo(100_000_000L - 15700 * 150 - 15800 * 50);
    }

    @Test
    public void checking_events() { //------------------
        Matcher.setLastPriceExecuted(15700);

        var req2 = new ChangeMatchingStateRq(security.getIsin(),
                MatchingState.AUCTION);
        orderHandler.handleChangeState(req2);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(any(), security.getIsin(), MatchingState.AUCTION));
        var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), BUY, 100, 15900,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);
        verify(eventPublisher).publish(new OpeningPriceEvent(any(), security.getIsin(), 15700, 0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 11));

        req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12,
                LocalDateTime.now(), BUY, 100, 15700,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);
        verify(eventPublisher).publish(new OpeningPriceEvent(any(), security.getIsin(), 15700, 0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 12));

        req = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 13,
                LocalDateTime.now(), SELL, 150, 15500,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        orderHandler.handleEnterOrder(req);
        verify(eventPublisher).publish(new OpeningPriceEvent(any(), security.getIsin(), 15700, 150));
        verify(eventPublisher).publish(new OrderAcceptedEvent(3, 13));

        req2 = new ChangeMatchingStateRq(security.getIsin(),
                MatchingState.CONTINUOUS);
        orderHandler.handleChangeState(req2);
        verify(eventPublisher).publish(new SecurityStateChangedEvent(any(), security.getIsin(), MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new TradeEvent(any(), security.getIsin(),
                100 * 15700, 100, 11,
                13));
        verify(eventPublisher).publish(new TradeEvent(any(), security.getIsin(),
                50 * 15700, 50, 12,
                13));
    }
}
