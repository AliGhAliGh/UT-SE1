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
import org.springframework.test.annotation.DirtiesContext.MethodMode;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext(methodMode = MethodMode.AFTER_METHOD)
public class MEQTest
{
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
        void new_order_not_satisfy_MEQ() {
                Order order = new Order(6, security, Side.BUY, 1000, 15800, broker, shareholder, 400);
                MatchResult result = matcher.match(order);
                assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_SATISFIED_MEQ);
                assertThat(result.remainder()).isNull();
                assertThat(result.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(15_000_000L);
        }

        @Test
        void new_iceberg_order_not_satisfy_MEQ_with_low_peak() {
                IcebergOrder order = new IcebergOrder(6, security, Side.BUY, 1000, 15800, broker, shareholder, 100, 400);
                MatchResult result = matcher.match(order);
                assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_SATISFIED_MEQ);
                assertThat(result.remainder()).isNull();
                assertThat(result.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(15_000_000L);
        }

        @Test
        void new_iceberg_order_not_satisfy_MEQ_with_high_peak() {
                IcebergOrder order = new IcebergOrder(6, security, Side.BUY, 1000, 15800, broker, shareholder, 500, 400);
                MatchResult result = matcher.match(order);
                assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_SATISFIED_MEQ);
                assertThat(result.remainder()).isNull();
                assertThat(result.trades()).isEmpty();
                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(5);
                assertThat(broker.getCredit()).isEqualTo(15_000_000L);
        }

//        @Test
//        void new_order_has_chance_not_roolback() {
//                Order order = new Order(6, security, Side.BUY, 945, 16000, broker,
//                                shareholder);
//                MatchResult result = matcher.match(order);
//                assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
//                assertThat(result.remainder().getTotalQuantity()).isEqualTo(0);
//                assertThat(result.trades()).isNotEmpty();
//                assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
//                assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(3);
//                assertThat(broker.getCredit()).isEqualTo(63_050);
//        }
}
