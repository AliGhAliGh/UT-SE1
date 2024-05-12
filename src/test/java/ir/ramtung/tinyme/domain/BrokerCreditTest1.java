package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
public class BrokerCreditTest1 {
    private Security security;
    private Broker buyer;
    private Broker seller;
    private long buyer_credit;
    private long seller_credit;
    private Shareholder shareholder;
    private List<Order> orders;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        buyer = Broker.builder().brokerId(0).credit(10_000_000L).build();
        seller = Broker.builder().brokerId(0).credit(10_000_000L).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000);
        buyer_credit = buyer.getCredit();
        seller_credit = seller.getCredit();
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, buyer, shareholder),
                new Order(2, security, Side.BUY, 43, 15500, buyer, shareholder),
                new Order(3, security, Side.BUY, 445, 15450, buyer, shareholder),
                new Order(4, security, Side.BUY, 526, 15450, buyer, shareholder),
                new Order(5, security, Side.BUY, 1000, 15400, buyer, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, seller, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, seller, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, seller, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, seller, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, seller, shareholder));
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void check_increase() {
        buyer.increaseCreditBy(1000000);

        long buyer_expected_value = buyer_credit + 1000000;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> seller.increaseCreditBy(-1000000));
    }

    @Test
    void check_decrease() {
        buyer.decreaseCreditBy(1000000);

        long buyer_expected_value = buyer_credit - 1000000;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> seller.decreaseCreditBy(-1000000));
    }

    @Test
    void check_has_enough() {
        assertThat(buyer.hasEnoughCredit(10000000)).isTrue();
        assertThat(buyer.hasEnoughCredit(10000001)).isFalse();
        assertThat(buyer.hasEnoughCredit(999999)).isTrue();

    }

    @Test
    void add_buy_order_matches_with_the_part_of_first_sell_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 100, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (15800 * 100);
        long seller_expected_value = seller_credit + (15800 * 100);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_buy_order_matches_with_the_all_of_first_sell_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 350, 15800, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (15800 * 350);
        long seller_expected_value = seller_credit + (15800 * 350);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_buy_order_matches_with_the_all_of_first_and_part_of_second_sell_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 400, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long expected_value = buyer_credit - (15800 * 350 + 50 * 15810);

        assertThat(buyer.getCredit()).isEqualTo(expected_value);
    }

    @Test
    void add_buy_order_matches_with_no_sell_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 100, 15000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (100 * 15000);
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_buy_order_matches_with_the_part_of_first_sell_order_and_has_remainder() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 400, 15805, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (350 * 15800 + 50 * 15805);
        long seller_expected_value = seller_credit + (350 * 15800);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_buy_order_matches_with_first_and_second_sell_order_and_negative_credit() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 800, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit;
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_sell_order_matches_with_the_part_of_first_buy_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.SELL, 100, 15000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (100 * 15700);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_sell_order_matches_with_the_all_of_first_buy_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.SELL, 304, 15700, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (304 * 15700);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_sell_order_matches_with_the_all_of_first_and_second_buy_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.SELL, 347, 15000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (304 * 15700 + 43 * 15500);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_sell_order_matches_with_no_buy_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.SELL, 100, 16000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit;
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_sell_order_matches_with_the_all_of_first_and_part_of_second_buy_order() {
        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.SELL, 350, 15460, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (304 * 15700 + 43 * 15500);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_buy_order_matches_with_the_part_of_first_sell_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 100, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700 - 100 * 15800);
        long seller_expected_value = seller_credit + (100 * 15800);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_order_matches_with_the_all_of_first_sell_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 350, 15800, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700 - 350 * 15800);
        long seller_expected_value = seller_credit + (350 * 15800);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_order_matches_with_the_all_of_first_and_part_of_second_sell_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 400, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700 - 350 * 15800 - 50 * 15810);
        long seller_expected_value = seller_credit + (350 * 15800 + 50 * 15810);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_order_matches_with_no_sell_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 100, 15000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700 - 100 * 15000);
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_order_matches_with_the_part_of_first_sell_order_and_has_remainder() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 400, 15805, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700 - 350 * 15800 - 50 * 15805);
        long seller_expected_value = seller_credit + (350 * 15800);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_order_matches_with_first_and_second_sell_order_and_negative_credit() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1,
                LocalDateTime.now(), Side.BUY, 800, 16000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit - 7871700;
        long seller_expected_value = seller_credit + 12644500;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_sell_order_matches_with_the_part_of_first_buy_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6,
                LocalDateTime.now(), Side.SELL, 100, 15000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long seller_expected_value = seller_credit + (100 * 15700);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_sell_order_matches_with_the_all_of_first_buy_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6,
                LocalDateTime.now(), Side.SELL, 304, 15700, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long seller_expected_value = seller_credit + (304 * 15700);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_sell_order_matches_with_the_all_of_first_and_second_buy_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6,
                LocalDateTime.now(), Side.SELL, 347, 15000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long seller_expected_value = seller_credit + (304 * 15700 + 43 * 15500);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_sell_order_matches_with_no_buy_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6,
                LocalDateTime.now(), Side.SELL, 100, 16000, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long seller_expected_value = seller_credit;
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_sell_order_matches_with_the_all_of_first_and_part_of_second_buy_order() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 6,
                LocalDateTime.now(), Side.SELL, 350, 15460, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long seller_expected_value = seller_credit + (304 * 15700 + 43 * 15500);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void delete_buy_order() {
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.BUY, 1);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));

        long buyer_expected_value = buyer_credit + (304 * 15700);
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void delete_sell_order() {
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.SELL, 6);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));

        long buyer_expected_value = buyer_credit;
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_order_matches_with_the_part_of_first_sell_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 120, 15750, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (120 * 15700);
        long seller_expected_value = seller_credit + (120 * 15700);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_order_matches_with_the_all_of_first_sell_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 300, 15750, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (250 * 15700 + 50 * 15750);
        long seller_expected_value = seller_credit + (250 * 15700);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_order_matches_with_the_part_of_first_sell_iceberg_and_another_matches_with_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 500, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 500, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq1 = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.BUY, 300, 15750, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq1, buyer, shareholder);

        EnterOrderRq orderReq2 = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 14,
                LocalDateTime.now(), Side.BUY, 300, 15750, buyer.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq2, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (300 * 15700 + 200 * 15700 + 100 * 15750);
        long seller_expected_value = seller_credit + (300 * 15700 + 200 * 15700);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_iceberg_matches_with_the_part_of_first_sell_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq1 = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.BUY, 150, 15750, buyer.getBrokerId(),
                shareholder.getShareholderId(), 100);
        security.newOrder(orderReq1, buyer, shareholder);

        long buyer_expected_value = buyer_credit - (150 * 15700);
        long seller_expected_value = seller_credit + (150 * 15700);

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void add_order_matches_with_the_part_of_first_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15760, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.SELL, 120, 15750, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (120 * 15760);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_order_matches_with_the_all_of_first_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15760, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.SELL, 300, 15750, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (250 * 15760);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_iceberg_matches_with_the_part_of_first_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15760, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.SELL, 120, 15750, seller.getBrokerId(),
                shareholder.getShareholderId(), 0);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (120 * 15760);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void add_iceberg_matches_with_the_part_of_first_and_second_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15760, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq orderReq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 13,
                LocalDateTime.now(), Side.SELL, 150, 15750, seller.getBrokerId(),
                shareholder.getShareholderId(), 100);
        security.newOrder(orderReq, seller, shareholder);

        long seller_expected_value = seller_credit + (150 * 15760);
        long buyer_expected_value = buyer_credit;

        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
    }

    @Test
    void update_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350,
                buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700,
                seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1,
                security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 250, 15800, buyer.getBrokerId(),
                shareholder.getShareholderId(), 100);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = 9912500;
        long seller_expected_value = seller_credit + 3925000;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_sell_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350,
                buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700,
                seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1,
                security.getIsin(), 12,
                LocalDateTime.now(), Side.SELL, 250, 15500, seller.getBrokerId(),
                shareholder.getShareholderId(), 100);

        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = buyer_credit;
        long seller_expected_value = seller_credit + 3925000;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void delete_buy_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.BUY, 11);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));

        long buyer_expected_value = buyer_credit + (250 * 15350);
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void delete_sell_iceberg() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 250, 15350, buyer, shareholder, 100);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 250, 15700, seller, shareholder, 100);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.SELL, 12);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));

        long buyer_expected_value = buyer_credit;
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }

    @Test
    void update_buy_iceberg_quantity() {
        IcebergOrder order1 = new IcebergOrder(11, security, Side.BUY, 1000, 12000,
                buyer, shareholder, 50);
        IcebergOrder order2 = new IcebergOrder(12, security, Side.SELL, 1000, 15700,
                seller, shareholder, 50);
        security.getOrderBook().enqueue(order1);
        security.getOrderBook().enqueue(order2);

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1,
                security.getIsin(), 11,
                LocalDateTime.now(), Side.BUY, 1500, 14000, buyer.getBrokerId(),
                shareholder.getShareholderId(), 100);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq));

        long buyer_expected_value = 1000000;
        long seller_expected_value = seller_credit;

        assertThat(buyer.getCredit()).isEqualTo(buyer_expected_value);
        assertThat(seller.getCredit()).isEqualTo(seller_expected_value);
    }
}