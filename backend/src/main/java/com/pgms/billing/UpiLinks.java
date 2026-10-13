package com.pgms.billing;

import com.pgms.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure helpers for UPI collection: the {@code upi://pay} deep link (also the QR payload; the client renders the image)
 * and the shape of a payment reference (UTR / bank reference / cheque number) per method.
 */
public final class UpiLinks {

    /** UPI apps truncate the transaction note; keep it short. */
    static final int MAX_NOTE = 50;

    private static final Pattern UPI_UTR = Pattern.compile("^\\d{12}$");
    private static final Pattern BANK_REFERENCE = Pattern.compile("^[A-Za-z0-9]{6,22}$");
    private static final Pattern CHEQUE_NUMBER = Pattern.compile("^\\d{6}$");
    private static final String HEX = "0123456789ABCDEF";

    private UpiLinks() {
    }

    /**
     * {@code upi://pay?pa=..&pn=..&am=..&cu=INR&tn=..&tr=..}; every value is percent-encoded (RFC 3986 unreserved
     * characters and the '@' of a VPA are kept, spaces become %20 rather than '+', which some UPI apps show literally).
     */
    public static String payUri(String vpa, String payeeName, BigDecimal amount, String invoiceNumber) {
        String note = "Rent " + invoiceNumber;
        if (note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE);
        }
        return "upi://pay?pa=" + encode(vpa)
                + "&pn=" + encode(payeeName)
                + "&am=" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "&cu=INR"
                + "&tn=" + encode(note)
                + "&tr=" + encode(invoiceNumber);
    }

    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~@".indexOf(c) >= 0)) {
                out.append(c);
            } else {
                out.append('%').append(HEX.charAt((b >> 4) & 0x0F)).append(HEX.charAt(b & 0x0F));
            }
        }
        return out.toString();
    }

    /**
     * Normalises and validates the reference of a claimed payment: UPI = 12-digit UTR, BANK_TRANSFER = 6-22
     * alphanumerics, CHEQUE = 6-digit cheque number, CASH = no reference (a note saying whom it was paid to is required).
     * Returns the upper-cased reference, or null for cash.
     */
    public static String normalizeReference(PaymentMethod method, String reference, String note) {
        String value = reference == null || reference.isBlank() ? null : reference.trim().replace(" ", "").toUpperCase(Locale.ROOT);
        switch (method) {
            case CASH -> {
                if (value != null) {
                    throw new BadRequestException("INVALID_UTR", "Cash payments have no transaction reference");
                }
                if (note == null || note.isBlank()) {
                    throw new BadRequestException("NOTE_REQUIRED", "Say whom the cash was paid to (e.g. \"Paid to warden Ravi\")");
                }
                return null;
            }
            case UPI -> require(value, UPI_UTR, "A UPI payment needs the 12-digit UTR / reference number");
            case BANK_TRANSFER -> require(value, BANK_REFERENCE, "A bank transfer needs its 6-22 character reference");
            case CHEQUE -> require(value, CHEQUE_NUMBER, "A cheque payment needs the 6-digit cheque number");
            default -> throw new BadRequestException("INVALID_CLAIM_METHOD", "Claims can be UPI, BANK_TRANSFER, CASH or CHEQUE");
        }
        return value;
    }

    private static void require(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new BadRequestException("INVALID_UTR", message);
        }
    }
}
