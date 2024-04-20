package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
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
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopPriceTest {
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

                orderBook = security.getOrderBook();
                orders = Arrays.asList(
                                new Order(6, security, Side.SELL, 200, 15800, brokerSell, shareholder),
                                new Order(7, security, Side.SELL, 285, 15810, brokerSell, shareholder),
                                new Order(8, security, Side.SELL, 800, 15810, brokerSell, shareholder),
                                new Order(9, security, Side.SELL, 340, 15820, brokerSell, shareholder),
                                new Order(10, security, Side.SELL, 65, 15820, brokerSell, shareholder));
                orders.forEach(order -> orderBook.enqueue(order));
        }

        @Test
        public void verify_published_messages() {
                var req = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 11, LocalDateTime.now(), BUY, 2, 15600,
                                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15750);
                orderHandler.handleEnterOrder(req);
                req = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 12, LocalDateTime.now(), BUY, 2, 15800,
                                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
                orderHandler.handleEnterOrder(req);
                verify(eventPublisher).publish(new OrderActivatedEvent(2, 11));
                assertThat(brokerBuy.getCredit()).isEqualTo(100_000_000 - 15800 * 2 - 15600 * 2);
                assertThat(orderBook.getBuyQueue().size()).isEqualTo(1);
                req = EnterOrderRq.createNewOrderRq(4, security.getIsin(), 13, LocalDateTime.now(), SELL, 3, 15600,
                                brokerSell.getBrokerId(), shareholder.getShareholderId(), 0);
                orderHandler.handleEnterOrder(req);
                verify(eventPublisher).publish(new OrderAcceptedEvent(4, 13));
                assertThat(brokerBuy.getCredit()).isEqualTo(100_000_000 - 15800 * 2 - 15600 * 2);
                assertThat(orderBook.getBuyQueue().size()).isEqualTo(0);
        }

        @Test
        public void deleting_deactivated_order() {
                var req = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), SELL, 2, 15700,
                                brokerSell.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15800);
                orderHandler.handleEnterOrder(req);
                var req2 = new DeleteOrderRq(2, security.getIsin(), SELL, 11);
                orderHandler.handleDeleteOrder(req2);
                req = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 12, LocalDateTime.now(), BUY, 2, 15800,
                                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
                orderHandler.handleEnterOrder(req);
                assertThat(brokerSell.getCredit()).isEqualTo(100_000_000 + 15800 * 2);
                assertThat(orderBook.getSellQueue().size()).isEqualTo(5);
                assertThat(orderBook.getDeactivatedQueue().size()).isEqualTo(0);

        }
}
