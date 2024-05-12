package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.MethodMode;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext(methodMode = MethodMode.AFTER_METHOD)
public class BrokerCreditTest2 {
        private Security security;
        private Broker broker;
        private Shareholder shareholder;
        private OrderBook orderBook;
        private List<Order> orders;

        @BeforeEach
        void setupOrderBook() {
                security = Security.builder().build();
                var b = Broker.builder().brokerId(1).build();
                broker = Broker.builder().brokerId(2).credit(15_000_000L).build();
                shareholder = Shareholder.builder().build();
                shareholder.incPosition(security, 100_000);
                orderBook = security.getOrderBook();
                orders = Arrays.asList(
                                new Order(1, security, Side.SELL, 350, 15800, b, shareholder),
                                new Order(2, security, Side.SELL, 285, 15810, b, shareholder),
                                new Order(3, security, Side.SELL, 800, 15810, b, shareholder),
                                new Order(4, security, Side.SELL, 340, 15820, b, shareholder),
                                new Order(5, security, Side.SELL, 65, 15820, b, shareholder));
                orders.forEach(order -> orderBook.enqueue(order));
        }

        @Test
        void new_order_negetive_credit_will_rollback() {
                Order order = new Order(6, security, Side.BUY, 1000, 16000, broker,
                                shareholder);
                MatchResult result = Matcher.match(order);
                assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_CREDIT);
                assertThat(result.remainder()).isNull();
                assertThat(result.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(15_000_000L);
        }

        @Test
        void new_order_has_chance_not_roolback() {
                Order order = new Order(6, security, Side.BUY, 945, 16000, broker,
                                shareholder);
                MatchResult result = Matcher.match(order);
                assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
                assertThat(result.remainder().getTotalQuantity()).isEqualTo(0);
                assertThat(result.trades()).isNotEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(3);
                assertThat(broker.getCredit()).isEqualTo(63_050);
        }

        @Test
        void enqueu_buy_order_must_have_enough_credit() {
                broker.decreaseCreditBy(15_000_000L);
                broker.increaseCreditBy(15450);
                var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 6, LocalDateTime.now(), Side.BUY, 1,
                                15451, broker.getBrokerId(), shareholder.getShareholderId(), 0);
                var res = security.newOrder(req, broker, shareholder);
                assertThat(res.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_CREDIT);
                assertThat(res.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(15450);
        }

        @Test
        void check_enough_credit_function_exactly() {
                broker.decreaseCreditBy(15_000_000L);
                broker.increaseCreditBy(15450);
                var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 6, LocalDateTime.now(), Side.BUY, 1,
                                15450, broker.getBrokerId(), shareholder.getShareholderId(), 0);
                var res = security.newOrder(req, broker, shareholder);
                assertThat(res.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isNotEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(0);
        }

        @Test
        void increase_credit_check() {
                orderBook.enqueue(new Order(6, security, Side.SELL, 100, 15000, broker, shareholder));
                var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 7, LocalDateTime.now(), Side.BUY, 110,
                                15450, 3, shareholder.getShareholderId(), 0);
                var res = security.newOrder(req, Broker.builder().credit(10_000_000).brokerId(3).build(), shareholder);
                assertThat(res.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
                assertThat(res.trades().size()).isEqualTo(1);
                assertThat(security.getOrderBook().getBuyQueue().getFirst().getTotalQuantity()).isEqualTo(10);
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(16_500_000L);
        }

        @Test
        void credit_check_after_two_iceberg_order_round() {
                var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 6, LocalDateTime.now(), Side.BUY, 400,
                                15805, 3, shareholder.getShareholderId(), 400);
                var res = security.newOrder(req, broker, shareholder);
                assertThat(res.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
                assertThat(res.trades().size()).isEqualTo(1);
                assertThat(security.getOrderBook().getBuyQueue().getFirst().getTotalQuantity()).isEqualTo(50);
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(4);
                assertThat(broker.getCredit()).isEqualTo(8_679_750L);
                System.out.println(orderBook.getBuyQueue().getFirst().getQuantity());
                var b = Broker.builder().brokerId(4).build();
                req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 7, LocalDateTime.now(), Side.SELL, 400,
                                15805, b.getBrokerId(), shareholder.getShareholderId(), 0);
                res = security.newOrder(req, b, shareholder);
                assertThat(res.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
                assertThat(res.trades().size()).isEqualTo(1);
                assertThat(res.trades().getFirst().getTradedValue()).isEqualTo(50 * 15805);// BUG
        }
}
