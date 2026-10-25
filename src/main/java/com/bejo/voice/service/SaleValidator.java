package com.bejo.voice.service;

import com.bejo.voice.dto.VoiceCatalogueProductDto;
import com.bejo.voice.dto.VoiceTransactionItemDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.TransactionUnderstanding.CatalogueEntry;
import com.bejo.voice.service.TransactionUnderstanding.Proposal;
import com.bejo.voice.service.TransactionUnderstanding.ProposedItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model said, made safe. A language model listening to audio that anyone can speak into is an untrusted source, so
 * everything it returns goes through here, field by field, whether it came back in one answer ({@link VoiceTransactionService})
 * or piece by piece while the merchant was still talking ({@link LiveSale}): a payment method outside the app's six becomes
 * null; text is trimmed, stripped of control characters and cut to size; a product id the request did not contain turns the
 * line into a new product; a matched product is always priced from the merchant's own catalogue, never from the speech;
 * quantities and amounts are clamped to sane bounds.
 */
final class SaleValidator {

    private static final int MAX_ITEMS = 20;
    private static final int MAX_QUANTITY = 9999;
    private static final int MAX_TRANSCRIPT = 500;
    private static final int MAX_INFO = 50;
    private static final int MAX_PRODUCT_NAME = 100;

    private final long maxAmount;

    SaleValidator(long maxAmount) {
        this.maxAmount = maxAmount;
    }

    VoiceTransactionResponseDto validated(Proposal heard, Map<Long, CatalogueEntry> catalogue) {
        Long amount = withinBounds(heard.amount());
        List<VoiceTransactionItemDto> items = items(heard.items(), catalogue);
        return new VoiceTransactionResponseDto(
                amount != null || !items.isEmpty(),
                clean(heard.transcript(), MAX_TRANSCRIPT),
                amount,
                paymentMethod(heard.paymentMethod()),
                clean(heard.customerInfo(), MAX_INFO),
                clean(heard.merchantInfo(), MAX_INFO),
                items);
    }

    private List<VoiceTransactionItemDto> items(List<ProposedItem> proposed, Map<Long, CatalogueEntry> catalogue) {
        List<VoiceTransactionItemDto> items = new ArrayList<>();
        Map<Long, Integer> matchedAt = new HashMap<>();   // catalogue id → its position in items
        for (ProposedItem p : proposed == null ? List.<ProposedItem>of() : proposed) {
            if (p == null) continue;
            int quantity = p.quantity() == null || p.quantity() < 1 ? 1 : (int) Math.min(p.quantity(), MAX_QUANTITY);
            // An id is only believed when the request itself contained it
            CatalogueEntry known = p.productId() == null ? null : catalogue.get(p.productId());
            if (known != null) {
                Integer at = matchedAt.get(known.id());
                if (at != null) {   // the same product said twice: one line, the quantities added
                    VoiceTransactionItemDto first = items.get(at);
                    items.set(at, new VoiceTransactionItemDto(first.productId(), first.name(),
                            Math.min(first.quantity() + quantity, MAX_QUANTITY), first.unitPrice(), false));
                } else {
                    matchedAt.put(known.id(), items.size());
                    // Name and price are the merchant's own; what was said about price is ignored for a product it has
                    items.add(new VoiceTransactionItemDto(known.id(), known.name(), quantity, known.price(), false));
                }
            } else {
                String name = clean(p.name(), MAX_PRODUCT_NAME);
                if (name == null) continue;   // nothing to show and nothing to create
                items.add(new VoiceTransactionItemDto(null, name, quantity, withinBounds(p.unitPrice()), true));
            }
        }
        return items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
    }

    Long withinBounds(Long rupiah) {
        return rupiah != null && rupiah >= 1 && rupiah <= maxAmount ? rupiah : null;
    }

    static String paymentMethod(String said) {
        String method = clean(said, 30);
        if (method == null) return null;
        for (String known : TransactionPrompt.PAYMENT_METHODS) {
            if (known.equalsIgnoreCase(method)) return known;
        }
        return null;
    }

    /**
     * Text from an untrusted source made fit to show and store: line breaks and tabs become spaces (runs collapsed),
     * other control and invisible formatting characters (zero-width, right-to-left overrides) are removed, the ends are
     * trimmed and the result is cut to {@code max} characters. Null when nothing is left.
     */
    static String clean(String raw, int max) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        int count = 0;
        boolean gap = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r' || cp == 0x0B || cp == '\f' || type == Character.SPACE_SEPARATOR
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                gap = true;
            } else if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE) {
                // dropped
            } else {
                if (gap && count > 0) {
                    if (count >= max) break;
                    out.append(' ');
                    count++;
                }
                gap = false;
                if (count >= max) break;
                out.appendCodePoint(cp);
                count++;
            }
        }
        String text = out.toString().stripTrailing();
        return text.isEmpty() ? null : text;
    }

    /** The merchant's products, cleaned: names stripped of line breaks and cut, nameless or repeated ids left out. */
    static Map<Long, CatalogueEntry> catalogue(List<VoiceCatalogueProductDto> sent) {
        Map<Long, CatalogueEntry> catalogue = new LinkedHashMap<>();
        for (VoiceCatalogueProductDto p : sent) {
            if (p == null || p.id() == null) continue;
            String name = clean(p.name() == null ? null : p.name().replace('|', '/'), MAX_PRODUCT_NAME);
            if (name == null) continue;
            catalogue.putIfAbsent(p.id(), new CatalogueEntry(p.id(), name, p.price()));
        }
        return catalogue;
    }
}
