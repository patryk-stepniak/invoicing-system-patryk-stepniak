package pl.futurecollars.invoicing.service.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import pl.futurecollars.invoicing.db.Database;
import pl.futurecollars.invoicing.model.Car;
import pl.futurecollars.invoicing.model.Company;
import pl.futurecollars.invoicing.model.Invoice;
import pl.futurecollars.invoicing.model.InvoiceEntry;

@Service
@AllArgsConstructor
public class TaxCalculatorService {

  private final Database<Invoice> database;

  public BigDecimal income(String taxIdentificationNumber) {
    return visit(sellerPredicate(taxIdentificationNumber), InvoiceEntry::getNetPrice);
  }

  public BigDecimal costs(String taxIdentificationNumber) {
    return visit(buyerPredicate(taxIdentificationNumber), this::getIncomeValueTakingIntoConsiderationPersonalCarUsage);
  }

  public BigDecimal collectedVat(String taxIdentificationNumber) { // vat we collect when selling products
    return visit(sellerPredicate(taxIdentificationNumber), InvoiceEntry::getVatValue);
  }

  public BigDecimal paidVat(String taxIdentificationNumber) { // vat we pay when buying products
    return visit(buyerPredicate(taxIdentificationNumber), this::getVatValueTakingIntoConsiderationPersonalCarUsage);
  }

  private BigDecimal getVatValueTakingIntoConsiderationPersonalCarUsage(InvoiceEntry invoiceEntry) {
    return Optional.ofNullable(invoiceEntry.getExpenseRelatedToCar())
        .map(Car::isPersonalUse)
        .map(personalCarUsage -> personalCarUsage ? BigDecimal.valueOf(5, 1) : BigDecimal.ONE)
        .map(proportion -> invoiceEntry.getVatValue().multiply(proportion))
        .map(value -> value.setScale(2, RoundingMode.FLOOR))
        .orElse(invoiceEntry.getVatValue());
  }

  private BigDecimal getIncomeValueTakingIntoConsiderationPersonalCarUsage(InvoiceEntry invoiceEntry) {
    return invoiceEntry.getNetPrice()
        // calling function instead of calculating proportion again allows us to keep logic in one place
        // and gives guarantee that unequal rounding value is calculated either in costs or vat
        .add(invoiceEntry.getVatValue())
        .subtract(getVatValueTakingIntoConsiderationPersonalCarUsage(invoiceEntry));
  }

  public BigDecimal getEarnings(String taxIdentificationNumber) {
    return income(taxIdentificationNumber).subtract(costs(taxIdentificationNumber));
  }

  public BigDecimal getVatToReturn(String taxIdentificationNumber) {
    return collectedVat(taxIdentificationNumber).subtract(paidVat(taxIdentificationNumber));
  }

  public TaxCalculatorResult calculateTaxes(Company company) {
    String taxIdentificationNumber = company.getTaxIdentificationNumber();

    BigDecimal incomeMinusCosts = getEarnings(taxIdentificationNumber);
    BigDecimal incomeMinusCostsMinusPensionInsurance = incomeMinusCosts.subtract(company.getPensionInsurance());
    BigDecimal incomeMinusCostsMinusPensionInsuranceRounded = incomeMinusCostsMinusPensionInsurance.setScale(0, RoundingMode.HALF_DOWN);
    BigDecimal incomeTax = incomeMinusCostsMinusPensionInsuranceRounded.multiply(BigDecimal.valueOf(19, 2));
    BigDecimal healthInsuranceToSubtract =
        company.getHealthInsurance().multiply(BigDecimal.valueOf(775)).divide(BigDecimal.valueOf(900), RoundingMode.HALF_UP);
    BigDecimal incomeTaxMinusHealthInsurance = incomeTax.subtract(healthInsuranceToSubtract);

    return TaxCalculatorResult.builder()
        .income(income(taxIdentificationNumber))
        .costs(costs(taxIdentificationNumber))
        .incomeMinusCosts(incomeMinusCosts)
        .pensionInsurance(company.getPensionInsurance())
        .incomeMinusCostsMinusPensionInsurance(incomeMinusCostsMinusPensionInsurance)
        .incomeMinusCostsMinusPensionInsuranceRounded(incomeMinusCostsMinusPensionInsuranceRounded)
        .incomeTax(incomeTax)
        .healthInsurancePaid(company.getHealthInsurance())
        .healthInsuranceToSubtract(healthInsuranceToSubtract)
        .incomeTaxMinusHealthInsurance(incomeTaxMinusHealthInsurance)
        .finalIncomeTax(incomeTaxMinusHealthInsurance.setScale(0, RoundingMode.DOWN))

        // vat
        .collectedVat(collectedVat(taxIdentificationNumber))
        .paidVat(paidVat(taxIdentificationNumber))
        .vatToReturn(getVatToReturn(taxIdentificationNumber))
        .build();
  }

  private Predicate<Invoice> sellerPredicate(String taxIdentificationNumber) {
    return invoice -> taxIdentificationNumber.equals(invoice.getSeller().getTaxIdentificationNumber());
  }

  private Predicate<Invoice> buyerPredicate(String taxIdentificationNumber) {
    return invoice -> taxIdentificationNumber.equals(invoice.getBuyer().getTaxIdentificationNumber());
  }

  private BigDecimal visit(
      Predicate<Invoice> invoicePredicate,
      Function<InvoiceEntry, BigDecimal> invoiceEntryToValue
  ) {
    return database.getAll().stream()
        .filter(invoicePredicate)
        .flatMap(i -> i.getEntries().stream())
        .map(invoiceEntryToValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

}
