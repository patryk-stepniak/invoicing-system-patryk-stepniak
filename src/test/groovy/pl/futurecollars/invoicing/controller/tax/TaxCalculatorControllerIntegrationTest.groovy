package pl.futurecollars.invoicing.controller.tax

import pl.futurecollars.invoicing.controller.AbstractControllerTest
import pl.futurecollars.invoicing.model.Car
import pl.futurecollars.invoicing.model.Company
import pl.futurecollars.invoicing.model.Invoice
import pl.futurecollars.invoicing.model.InvoiceEntry
import pl.futurecollars.invoicing.model.Vat
import spock.lang.Unroll

import java.time.LocalDate

import static pl.futurecollars.invoicing.helpers.TestHelpers.company

@Unroll
class TaxCalculatorControllerIntegrationTest extends AbstractControllerTest {

    def "zeros are returned when there are no invoices in the system"() {
        when:
        def taxCalculatorResponse = calculateTax(company(0))

        then:
        with(taxCalculatorResponse) {
            income == 0
            costs == 0
            incomeMinusCosts == 0
            collectedVat == 0
            paidVat == 0
            vatToReturn == 0
        }
    }

    def "zeros are returned when tax id is not matching"() {
        given:
        addUniqueInvoices(10)

        when:
        def taxCalculatorResponse = calculateTax(company(-14))

        then:
        with(taxCalculatorResponse) {
            income == 0
            costs == 0
            incomeMinusCosts == 0
            collectedVat == 0
            paidVat == 0
            vatToReturn == 0
        }
    }

    def "sum of all products is returned when tax id is matching"() {
        given:
        addUniqueInvoices(10)

        when:
        def taxCalculatorResponse = calculateTax(company(5))

        then:
        with(taxCalculatorResponse) {
            income == 15000
            costs == 0
            incomeMinusCosts == 15000
            collectedVat == 1200.0
            paidVat == 0
            vatToReturn == 1200.0
        }

        when:
        taxCalculatorResponse = calculateTax(company(10))

        then:
        with(taxCalculatorResponse) {
            income == 55000
            costs == 0
            incomeMinusCosts == 55000
            collectedVat == 4400.0
            paidVat == 0
            vatToReturn == 4400.0
        }

        when:
        taxCalculatorResponse = calculateTax(company(15))

        then:
        with(taxCalculatorResponse) {
            income == 0
            costs == 15000
            incomeMinusCosts == -15000
            collectedVat == 0
            paidVat == 1200.0
            vatToReturn == -1200.0
        }
    }

    def "correct values are returned when company was buyer and seller"() {
        given:
        addUniqueInvoices(15) // sellers: 1-15, buyers: 10-25, 10-15 overlapping

        when:
        def taxCalculatorResponse = calculateTax(company(12))

        then:
        with(taxCalculatorResponse) {
            income == 78000
            costs == 3000
            incomeMinusCosts == 75000
            collectedVat == 6240.0
            paidVat == 240.0
            vatToReturn == 6000.0
        }
    }

    def "tax is calculated correctly when car is used for personal purposes"() {
        given:
        def invoice = Invoice.builder()
                .date(LocalDate.now())
                .number("no number :)")
                .seller(company(1))
                .buyer(company(2))
                .entries(List.of(
                        InvoiceEntry.builder()
                                .vatValue(BigDecimal.valueOf(23.45))
                                .vatRate(Vat.VAT_23)
                                .netPrice(BigDecimal.valueOf(100))
                                .quantity(1.0)
                                .expenseRelatedToCar(
                                        Car.builder()
                                                .personalUse(true)
                                                .registrationNumber("KWI 555234")
                                                .build()
                                )
                                .build()
                ))
                .build()

        addInvoiceAndReturnId(invoice)

        when:
        def taxCalculatorResponse = calculateTax(invoice.getSeller())

        then: "no proportion - it applies only when you are the buyer"
        with(taxCalculatorResponse) {
            income == 100
            costs == 0
            incomeMinusCosts == 100
            collectedVat == 23.45
            paidVat == 0
            vatToReturn == 23.45
        }

        when:
        taxCalculatorResponse = calculateTax(invoice.getBuyer())

        then: "proportion applied - it applies when you are the buyer"
        with(taxCalculatorResponse) {
            income == 0
            costs == 111.73
            incomeMinusCosts == -111.73
            collectedVat == 0
            paidVat == 11.72
            vatToReturn == -11.72
        }
    }

    def "All calculations are executed correctly"() {
        given:
        def ourCompany = Company.builder()
                .taxIdentificationNumber("1234")
                .address("no address exception ;)")
                .name("i don't care about name")
                .pensionInsurance(514.57)
                .healthInsurance(319.94)
                .build()

        def invoiceWithIncome = Invoice.builder()
                .date(LocalDate.now())
                .number("number is required")
                .seller(ourCompany)
                .buyer(company(2))
                .entries(List.of(
                        InvoiceEntry.builder()
                                .netPrice(76011.62)
                                .vatValue(0.0)
                                .quantity(1.0)
                                .vatRate(Vat.VAT_0)
                                .build()
                ))
                .build()

        def invoiceWithCosts = Invoice.builder()
                .date(LocalDate.now())
                .number("number is required")
                .seller(company(4))
                .buyer(ourCompany)
                .entries(List.of(
                        InvoiceEntry.builder()
                                .netPrice(11329.47)
                                .vatValue(0.0)
                                .quantity(1.0)
                                .vatRate(Vat.VAT_ZW)
                                .build()
                ))
                .build()

        addInvoiceAndReturnId(invoiceWithIncome)
        addInvoiceAndReturnId(invoiceWithCosts)

        when:
        def taxCalculatorResponse = calculateTax(ourCompany)

        then:
        with(taxCalculatorResponse) {
            income == 76011.62
            costs == 11329.47
            incomeMinusCosts == 64682.15
            pensionInsurance == 514.57
            incomeMinusCostsMinusPensionInsurance == 64167.58
            incomeMinusCostsMinusPensionInsuranceRounded == 64168
            incomeTax == 12191.92
            healthInsurancePaid == 319.94
            healthInsuranceToSubtract == 275.50
            incomeTaxMinusHealthInsurance == 11916.42
            finalIncomeTax == 11916

            collectedVat == 0
            paidVat == 0
            vatToReturn == 0
        }
    }

}
