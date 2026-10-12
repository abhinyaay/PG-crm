package com.pgms.billing;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.pgms.common.exception.NotFoundException;
import com.pgms.config.AppProperties;
import com.pgms.property.Property;
import com.pgms.property.PropertyRepository;
import com.pgms.tenant.Tenant;
import com.pgms.tenant.TenantRepository;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Renders a payment receipt as PDF on demand (receipts are derived from immutable payment rows, so not stored). */
@Service
public class ReceiptService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final PaymentService paymentService;
    private final InvoiceRepository invoiceRepository;
    private final TenantRepository tenantRepository;
    private final PropertyRepository propertyRepository;
    private final ZoneId zone;

    public ReceiptService(PaymentService paymentService, InvoiceRepository invoiceRepository, TenantRepository tenantRepository,
                          PropertyRepository propertyRepository, AppProperties properties) {
        this.paymentService = paymentService;
        this.invoiceRepository = invoiceRepository;
        this.tenantRepository = tenantRepository;
        this.propertyRepository = propertyRepository;
        this.zone = properties.businessTimezone();
    }

    public record Receipt(String receiptNumber, byte[] pdf) {
    }

    @Transactional(readOnly = true)
    public Receipt render(Long paymentId) {
        Payment payment = paymentService.loadAccessible(paymentId);
        Invoice invoice = invoiceRepository.findById(payment.getInvoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice", payment.getInvoiceId()));
        Tenant tenant = tenantRepository.findById(payment.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant", payment.getTenantId()));
        Property property = propertyRepository.findById(payment.getPropertyId())
                .orElseThrow(() -> new NotFoundException("Property", payment.getPropertyId()));

        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Receipt No.", payment.getReceiptNumber());
        rows.put("Date", TIMESTAMP.format(payment.getPaidAt().atZone(zone)));
        rows.put("Received from", tenant.getFullName());
        rows.put("Invoice", invoice.getInvoiceNumber() + " (" + invoice.getInvoiceType() + ")");
        if (invoice.getPeriodStart() != null) {
            rows.put("Billing period", invoice.getPeriodStart() + " to " + invoice.getPeriodEnd());
        }
        rows.put("Amount received", invoice.getCurrency() + " " + payment.getAmount().toPlainString());
        rows.put("Payment method", payment.getMethod().name());
        if (payment.getExternalReference() != null) {
            rows.put("Transaction reference", payment.getExternalReference());
        }
        if (payment.getRefundedAmount().signum() > 0) {
            rows.put("Refunded", invoice.getCurrency() + " " + payment.getRefundedAmount().toPlainString());
        }
        rows.put("Invoice balance", invoice.getCurrency() + " " + invoice.balance().toPlainString());
        return new Receipt(payment.getReceiptNumber(), toPdf(property, rows));
    }

    private static byte[] toPdf(Property property, Map<String, String> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A5);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Paragraph heading = new Paragraph(property.getName(), title);
            heading.setAlignment(Element.ALIGN_CENTER);
            document.add(heading);
            Paragraph address = new Paragraph(property.getAddress().getLine1() + ", " + property.getAddress().getCity()
                    + " " + property.getAddress().getPostalCode(), small);
            address.setAlignment(Element.ALIGN_CENTER);
            document.add(address);
            Paragraph label = new Paragraph("PAYMENT RECEIPT", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12));
            label.setAlignment(Element.ALIGN_CENTER);
            label.setSpacingBefore(12);
            label.setSpacingAfter(12);
            document.add(label);

            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(100);
            Font key = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font value = FontFactory.getFont(FontFactory.HELVETICA, 10);
            rows.forEach((k, v) -> {
                table.addCell(new PdfPCell(new Paragraph(k, key)));
                table.addCell(new PdfPCell(new Paragraph(v, value)));
            });
            document.add(table);
            Paragraph footer = new Paragraph("This is a system-generated receipt and does not require a signature.", small);
            footer.setSpacingBefore(16);
            document.add(footer);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render receipt", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }
}
