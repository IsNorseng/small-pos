package ru.smallpos.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Business rules use integer kopecks and integer grams; no Android dependency. */
public final class Domain {
    public enum Role { CLIENT, SELLER, ADMIN }
    public static final long MAX_TOTAL = 999999999L;
    public static final class Product {
        public final long id, price;
        public final String name, unit;
        public final boolean active;
        public Product(long id, String name, String unit, long price, boolean active) {
            if (id < 1 || name == null || name.trim().isEmpty() || name.trim().length() > 80)
                throw new IllegalArgumentException("Укажите название товара, не длиннее 80 символов.");
            if (!("piece".equals(unit) || "kg".equals(unit))) throw new IllegalArgumentException("Неизвестная единица продажи.");
            if (price < 1 || price > 99999999) throw new IllegalArgumentException("Цена должна быть от 0,01 до 999 999,99 ₽.");
            this.id=id; this.name=name.trim(); this.unit=unit; this.price=price; this.active=active;
        }
    }
    public static final class Line {
        public final Product product;
        public final long quantity, amount;
        public Line(Product product, long quantity) {
            this.product=product; this.quantity=quantity; this.amount=amount(product.price, quantity, product.unit);
        }
    }
    private Domain() {}
    public static long amount(long price, long quantity, String unit) {
        if (price < 1 || price > 99999999 || quantity < 1 || quantity > ("kg".equals(unit)?100000:999))
            throw new IllegalArgumentException("Проверьте количество и цену товара.");
        if (!("kg".equals(unit) || "piece".equals(unit))) throw new IllegalArgumentException("Неизвестная единица продажи.");
        long value=Math.multiplyExact(price, quantity);
        if ("kg".equals(unit)) value=(value+500)/1000;
        if (value < 1 || value > MAX_TOTAL) throw new IllegalArgumentException("Сумма позиции вне допустимого диапазона.");
        return value;
    }
    public static long parsePrice(String value) {
        String normalized=value==null?"":value.trim().replace(',', '.');
        if (!normalized.matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Введите цену, например 180 или 180,50.");
        long result=new BigDecimal(normalized).movePointRight(2).longValueExact();
        if (result<1 || result>99999999) throw new IllegalArgumentException("Цена должна быть больше нуля.");
        return result;
    }
    public static long parseQuantity(String value, String unit) {
        String normalized=value==null?"":value.trim().replace(',', '.');
        boolean weight="kg".equals(unit);
        if (!("piece".equals(unit)||weight) || !normalized.matches(weight?"[0-9]{1,3}(\\.[0-9]{1,3})?":"[0-9]{1,3}"))
            throw new IllegalArgumentException(weight?"Введите вес в кг, например 0,650.":"Введите целое количество от 1 до 999.");
        long result=new BigDecimal(normalized).movePointRight(weight?3:0).longValueExact();
        if (result<1 || result>(weight?100000:999)) throw new IllegalArgumentException(weight?"Вес должен быть от 0,001 до 100 кг.":"Количество должно быть от 1 до 999.");
        return result;
    }
    public static String money(long kopecks) {
        DecimalFormat format=new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(new Locale("ru","RU")));
        format.setRoundingMode(RoundingMode.UNNECESSARY);
        return format.format(BigDecimal.valueOf(kopecks,2))+" ₽";
    }
    public static String quantity(long value, String unit) {
        return "kg".equals(unit)?BigDecimal.valueOf(value,3).stripTrailingZeros().toPlainString().replace('.', ',')+" кг":value+" шт.";
    }
    public static long total(List<Line> lines) {
        if (lines.isEmpty() || lines.size()>100) throw new IllegalArgumentException("Выберите от 1 до 100 товаров.");
        long result=0;
        for (Line line:lines) result=Math.addExact(result,line.amount);
        if (result>MAX_TOTAL) throw new IllegalArgumentException("Сумма заказа слишком велика.");
        return result;
    }
    public static String fingerprint(Map<Long,Long> cart, long revision, long total, String method) {
        List<Long> ids=new ArrayList<>(cart.keySet()); Collections.sort(ids);
        StringBuilder source=new StringBuilder().append(revision).append('|').append(total).append('|').append(method);
        for (Long id:ids) source.append('|').append(id).append(':').append(cart.get(id));
        return sha256(source.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] bytes) {
        try {return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch (Exception error) {throw new IllegalStateException("Не удалось проверить данные.",error);}
    }
    public static String salt() {byte[] value=new byte[24];new SecureRandom().nextBytes(value);return hex(value);}
    public static void validatePin(String pin) {
        if (pin==null || !pin.matches("[0-9]{6,12}")) throw new IllegalArgumentException("PIN должен содержать от 6 до 12 цифр.");
    }
    public static String pinHash(String pin,String salt) {
        validatePin(pin);
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt.getBytes(StandardCharsets.UTF_8),120000,256);
        try {return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        catch (Exception error) {throw new IllegalStateException("Не удалось защитить PIN.",error);}
        finally {spec.clearPassword();}
    }
    public static boolean sameHash(String a,String b) {
        return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));
    }
    /** 1: settled, 0: pending, -1: rejected. Exact identity and amount always required. */
    public static int bankOutcome(String expectedNumber,long expectedTotal,String number,long total,String currency,int status,String paymentState,long deposited,long refunded){
        if(!expectedNumber.equals(number)||expectedTotal!=total||!"643".equals(currency))throw new IllegalArgumentException("Данные платежа не совпадают с заказом.");
        if(status==3||status==6)return -1;
        if(status!=2)return 0;
        if(!"DEPOSITED".equals(paymentState)||deposited!=total||refunded!=0)throw new IllegalArgumentException("Банк ещё не подтвердил полное получение суммы.");
        return 1;
    }
    private static String hex(byte[] bytes) {StringBuilder result=new StringBuilder(bytes.length*2);for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
}
