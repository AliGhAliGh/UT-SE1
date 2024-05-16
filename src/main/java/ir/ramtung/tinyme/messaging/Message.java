package ir.ramtung.tinyme.messaging;

public class Message {
    public static final String INVALID_ORDER_ID = "Invalid order ID";
    public static final String ORDER_QUANTITY_NOT_POSITIVE = "Order quantity is not-positive";
    public static final String ORDER_PRICE_NOT_POSITIVE = "Order price is not-positive";
    public static final String UNKNOWN_SECURITY_ISIN = "Unknown security ISIN";
    public static final String ORDER_ID_NOT_FOUND = "Order ID not found in the order book";
    public static final String INVALID_PEAK_SIZE = "Iceberg order peak size is out of range";
    public static final String CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER = "Cannot specify peak size for a non-iceberg order";
    public static final String UNKNOWN_BROKER_ID = "Unknown broker ID";
    public static final String UNKNOWN_SHAREHOLDER_ID = "Unknown shareholder ID";
    public static final String BUYER_HAS_NOT_ENOUGH_CREDIT = "Buyer has not enough credit";
    public static final String QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE = "Quantity is not a multiple of security lot size";
    public static final String PRICE_NOT_MULTIPLE_OF_TICK_SIZE = "Price is not a multiple of security tick size";
    public static final String SELLER_HAS_NOT_ENOUGH_POSITIONS = "Seller has not enough positions";
    public static final String ORDER_MEQ_NOT_POSITIVE = "Order MEQ is not-positive";
    public static final String ORDER_QUANTITY_SMALLER_THAN_MEQ = "Order quantity is smaller that MEQ";
    public static final String ORDER_NOT_SATISFIED_MEQ = "Order is not satisfied MEQ";
    public static final String ACTIVE_ORDER_STOP_LIMIT_UPDATE = "Active Orders can't update their stop limit!";
    public static final String MEQ_WITH_STOP_LIMIT_ORDER = "Stop limit orders can't have Minimum execution quantity!";
    public static final String ICEBERG_WITH_STOP_LIMIT_ORDER = "Stop limit orders can't be an iceberg order!";
    public static final String MEQ_IN_AUCTION_STATE = "MEQ is not allowed in auction state!";
    public static final String STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Stop limit order is not allowed in auction state!";
    public static final String DELETE_STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Delete Stop limit order is not allowed in auction state!";
    public static final String UPDATE_STOP_LIMIT_ORDER_IN_AUCTION_STATE = "Update Stop limit order is not allowed in auction state!";
}
