package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.MatchingOutcome.EXECUTED;
import static ir.ramtung.tinyme.domain.entity.MatchingOutcome.NOT_SATISFY_MEQ;
import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class MEQTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, BUY, 200, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new Order(3, security, BUY, 445, 15450, broker, shareholder),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void new_sell_order_will_rollback_because_of_meq() {
        var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.SELL, 300, 15700,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 250);
        var result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(NOT_SATISFY_MEQ);
        assertThat(result.trades()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        assertThat(broker.getCredit()).isEqualTo(100_000_000);
    }

    @Test
    void new_sell_order_will_accept_with_meq() {
        var req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 12, LocalDateTime.now(), Side.SELL, 300, 15700,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 200);
        var result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(EXECUTED);
        assertThat(result.trades()).isNotEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(4);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(6);
        assertThat(broker.getCredit()).isEqualTo(103_140_000);
    }

    @Test
    void new_buy_order_will_rollback_because_of_meq() {
        var req = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 13, LocalDateTime.now(), BUY, 300, 15800,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 250);
        var result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(NOT_SATISFY_MEQ);
        assertThat(result.trades()).isEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
        assertThat(broker.getCredit()).isEqualTo(100_000_000);
    }

    @Test
    void new_buy_order_will_accept_with_meq() {
        var req = EnterOrderRq.createNewOrderRq(4, security.getIsin(), 14, LocalDateTime.now(), BUY, 300, 15800,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 200);
        var result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(EXECUTED);
        assertThat(result.trades()).isNotEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(6);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(4);
        assertThat(broker.getCredit()).isEqualTo(98_420_000);
    }

    @Test
    void old_buy_order_will_accept_with_meq() {
        var req = EnterOrderRq.createNewOrderRq(4, security.getIsin(), 14, LocalDateTime.now(), BUY, 300, 15800,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 200);
        var result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(EXECUTED);
        assertThat(result.trades()).isNotEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(6);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(4);
        assertThat(broker.getCredit()).isEqualTo(98_420_000);
        req = EnterOrderRq.createNewOrderRq(4, security.getIsin(), 14, LocalDateTime.now(), SELL, 100, 15800,
                broker.getBrokerId(), shareholder.getShareholderId(), 0, 100);
        result = security.newOrder(req, broker, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(EXECUTED);
        assertThat(result.trades()).isNotEmpty();
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(5);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(4);
        assertThat(broker.getCredit()).isEqualTo(100_000_000);
    }
}
