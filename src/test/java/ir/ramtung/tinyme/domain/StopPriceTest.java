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
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.MatchingOutcome.DEACTIVATED;
import static ir.ramtung.tinyme.domain.entity.MatchingOutcome.EXECUTED;
import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;

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
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        brokerSell = Broker.builder().credit(100_000_000L).build();
        brokerBuy = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, BUY, 200, 15700, brokerBuy, shareholder),
                new Order(2, security, BUY, 43, 15500, brokerBuy, shareholder),
                new Order(3, security, BUY, 445, 15450, brokerBuy, shareholder),
                new Order(4, security, BUY, 526, 15450, brokerBuy, shareholder),
                new Order(5, security, BUY, 1000, 15400, brokerBuy, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, brokerSell, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, brokerSell, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, brokerSell, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, brokerSell, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, brokerSell, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    public void crossing_the_stop_price_will_activate_order() {
        var req2 = EnterOrderRq.createNewOrderRq(2, security.getIsin(), 11, LocalDateTime.now(), BUY, 2, 15600,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15750);
        var result = security.newOrder(req2, brokerBuy, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(DEACTIVATED);
        req2 = EnterOrderRq.createNewOrderRq(3, security.getIsin(), 12, LocalDateTime.now(), BUY, 2, 15800,
                brokerBuy.getBrokerId(), shareholder.getShareholderId(), 0);
        result = security.newOrder(req2, brokerBuy, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(brokerBuy.getCredit()).isEqualTo(100_000_000 - 15800 * 2 - 15600 * 2);
    }
}
