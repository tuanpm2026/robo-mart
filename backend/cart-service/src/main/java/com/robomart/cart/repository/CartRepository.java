package com.robomart.cart.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.robomart.cart.config.CartProperties;
import com.robomart.cart.entity.Cart;
import com.robomart.cart.entity.CartItem;
import com.robomart.cart.exception.CartItemNotFoundException;
import com.robomart.cart.exception.CartNotFoundException;

import tools.jackson.databind.ObjectMapper;

/** One hash per cart, atomic line operations and transfer, plus expiring product reverse indexes. */
@Repository
public class CartRepository {
    public static final String EXPIRY_KEY = "cart:expiry";
    private static final DefaultRedisScript<List> CART = script("redis/cart.lua", List.class);
    private static final DefaultRedisScript<Long> CATALOG = script("redis/cart-catalog.lua", Long.class);
    private static final DefaultRedisScript<Long> PRICE = script("redis/cart-price.lua", Long.class);

    private static <T> DefaultRedisScript<T> script(String resource, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(type);
        return script;
    }
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CartProperties properties;

    public CartRepository(StringRedisTemplate redis, ObjectMapper mapper, CartProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    public Cart add(String id, CartItem item, String user) {
        String json = mapper.writeValueAsString(Map.of("productId", item.getProductId().toString(),
                "productName", item.getProductName(), "price", item.getPrice().toPlainString(),
                "quantity", Math.min(Cart.MAX_ITEM_QUANTITY, item.getQuantity())));
        return execute("ADD", id, List.of(key(id), EXPIRY_KEY), item.getProductId().toString(), json,
                user == null ? "" : user);
    }

    public Cart setQuantity(String id, Long product, int quantity) {
        return execute("SET", id, List.of(key(id), EXPIRY_KEY), product.toString(), String.valueOf(quantity));
    }

    public void remove(String id, Long product) {
        execute("REMOVE", id, List.of(key(id), EXPIRY_KEY), product.toString());
    }

    public Cart getAndRefresh(String id) {
        return execute("GET", id, List.of(key(id), EXPIRY_KEY));
    }

    /** Scheduler read: does not refresh a cart's lifetime. */
    public Optional<Cart> findById(String id) {
        try {
            return Optional.of(execute("PEEK", id, List.of(key(id), EXPIRY_KEY)));
        } catch (CartNotFoundException missing) {
            return Optional.empty();
        }
    }

    public Cart merge(String guest, String user, String userId) {
        return execute("MERGE", user, List.of(key(user), EXPIRY_KEY, key(guest)), userId);
    }

    public int updateProduct(long product, String name, BigDecimal price, Instant timestamp) {
        redis.execute(CATALOG, List.of("cart:catalog:" + product), mapper.writeValueAsString(Map.of(
                "productName", name, "price", price.toPlainString(), "priceUpdatedAt", timestamp.toEpochMilli())),
                String.valueOf(properties.getTtlSeconds() + 3600));
        String index = "cart:product:" + product;
        redis.opsForZSet().removeRangeByScore(index, Double.NEGATIVE_INFINITY, Instant.now().getEpochSecond());
        int count = 0;
        try (var cursor = redis.opsForZSet().scan(index, ScanOptions.scanOptions().count(100).build())) {
            while (cursor.hasNext()) {
                String cart = cursor.next().getValue();
                Long updated = redis.execute(PRICE, List.of(cart, index), String.valueOf(product),
                        price.toPlainString(), name, String.valueOf(timestamp.toEpochMilli()));
                count += updated == null ? 0 : updated.intValue();
            }
        }
        return count;
    }

    private Cart execute(String operation, String id, List<String> keys, String... extra) {
        List<String> args = new ArrayList<>(List.of(operation, String.valueOf(properties.getTtlSeconds()),
                Instant.now().toString()));
        args.addAll(List.of(extra));
        List<?> values = redis.execute(CART, keys, args.toArray());
        if (values == null || values.isEmpty() || "MISSING_CART".equals(values.getFirst())) {
            throw new CartNotFoundException(id);
        }
        if ("MISSING_ITEM".equals(values.getFirst())) {
            throw new CartItemNotFoundException(id, Long.valueOf(extra[0]));
        }
        Cart cart = new Cart(id);
        cart.setTimeToLive(properties.getTtlSeconds());
        List<CartItem> items = new ArrayList<>();
        for (int i = 0; i < values.size(); i += 2) {
            String field = values.get(i).toString();
            String value = values.get(i + 1).toString();
            if (field.startsWith("p:")) {
                var item = mapper.readTree(value);
                items.add(new CartItem(Long.valueOf(item.get("productId").asText()),
                        item.get("productName").asText(), new BigDecimal(item.get("price").asText()),
                        item.get("quantity").asInt()));
            } else if ("__user".equals(field)) {
                cart.setUserId(value.isEmpty() ? null : value);
            } else if ("__created".equals(field)) {
                cart.setCreatedAt(Instant.parse(value));
            } else if ("__updated".equals(field)) {
                cart.setUpdatedAt(Instant.parse(value));
            }
        }
        items.sort(java.util.Comparator.comparing(CartItem::getProductId));
        cart.setItems(items);
        return cart;
    }

    public static String key(String id) {
        return "cart:" + id;
    }
}
