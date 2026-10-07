package vn.machtrip.pipeline.extract;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that price numbers produced by the model are consistent with the digits of the original {@code price_text}:
 * each number must be one of the figures that text can express in VND ("50k" -> 50000, "400.000" -> 400000, "2 củ" ->
 * 2000000, "1tr5" -> 1500000, "1,5 triệu" -> 1500000). It never invents a price; it only accepts or rejects.
 */
final class PriceCheck {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+");
    private static final Pattern COMPOUND_MILLION = Pattern.compile("(\\d+)\\s*(?:tr|triệu|trieu|m|củ|cu)\\s*(\\d)(?!\\d)");
    private static final Pattern COMPOUND_THOUSAND = Pattern.compile("(\\d+)\\s*(?:k|nghìn|ngàn|nghin|ngan)\\s*(\\d)(?!\\d)");

    private PriceCheck() {
    }

    static boolean consistent(String priceText, Long min, Long max) {
        if (min == null && max == null) {
            return true;
        }
        if (priceText == null || priceText.isBlank()) {
            return false;
        }
        Set<Long> allowed = candidates(priceText);
        return (min == null || allowed.contains(min)) && (max == null || allowed.contains(max)) && !(
                min != null && max != null && min > max);
    }

    static Set<Long> candidates(String priceText) {
        String t = priceText.toLowerCase(Locale.ROOT);
        Set<Long> out = new HashSet<>();
        Matcher m = NUMBER.matcher(t);
        while (m.find()) {
            double v = value(m.group());
            for (double unit : new double[]{1, 1_000, 1_000_000}) {
                out.add(Math.round(v * unit));
                if (t.contains("rưỡi") || t.contains("ruoi") || t.contains("rưởi")) {
                    out.add(Math.round(v * unit + unit / 2));
                }
            }
        }
        Matcher mm = COMPOUND_MILLION.matcher(t);
        while (mm.find()) {
            out.add(Long.parseLong(mm.group(1)) * 1_000_000 + Long.parseLong(mm.group(2)) * 100_000);
        }
        Matcher mt = COMPOUND_THOUSAND.matcher(t);
        while (mt.find()) {
            out.add(Long.parseLong(mt.group(1)) * 1_000 + Long.parseLong(mt.group(2)) * 100);
        }
        return out;
    }

    /** "400.000" / "1,500,000" are grouped integers; "1,5" / "2.5" are decimals; anything odd falls back to digits. */
    private static double value(String token) {
        if (GROUPED.matcher(token).matches()) {
            return Double.parseDouble(token.replaceAll("[.,]", ""));
        }
        String[] parts = token.split("[.,]");
        if (parts.length == 2) {
            return Double.parseDouble(parts[0] + "." + parts[1]);
        }
        return Double.parseDouble(token.replaceAll("[.,]", ""));
    }
}
