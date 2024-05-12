package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                                new Order(6, security, Side.SELL, 200, 15800, brokerSell, shareholder),
                                new Order(7, security, Side.SELL, 200, 15810, brokerSell, shareholder),
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
                                new Order(6, security, Side.SELL, 200, 15800, brokerSell, shareholder),
                                new Order(6, security, Side.BUY, 1, 15900, brokerBuy, shareholder),
                                new Order(7, security, Side.BUY, 200, 15910, brokerBuy, shareholder));
                orders.forEach(order -> orderBook.enqueue(order));
                Matcher.setLastPriceExecuted(15920);
                assertThat(security.getOpeningPrice(Matcher.getLastPriceExecuted())).isEqualTo(15910);
        }
}
