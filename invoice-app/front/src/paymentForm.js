export const PAYMENT_STATE_ERROR =
  'Created means zero paid, prepaid means a partial payment, and paid means the full invoice sum.';

const amount = (value) => {
  if (value === '' || value == null) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const isStrictPartial = (total, paid) =>
  total != null && total > 0 && paid != null && paid > 0 && paid < total;

export function updatePaymentField(form, field, value) {
  return {
    ...form,
    [field]: value,
    ...(field === 'invoice_sum' && form.invoice_status === 'paid'
      ? { invoice_sum_paid: value }
      : {}),
  };
}

export function updatePaymentStatus(form, status) {
  const total = amount(form.invoice_sum);
  const paid = amount(form.invoice_sum_paid);

  return {
    ...form,
    invoice_status: status,
    ...(status === 'paid'
      ? { invoice_sum_paid: form.invoice_sum }
      : status === 'created'
        ? { invoice_sum_paid: 0 }
        : status === 'prepaid' && !isStrictPartial(total, paid)
          ? { invoice_sum_paid: '' }
          : {}),
  };
}

export function paymentStateError(form) {
  const total = amount(form.invoice_sum);
  const paid = amount(form.invoice_sum_paid);

  if (total == null || total <= 0) {
    return 'Invoice sum must be greater than zero.';
  }
  if (paid != null && (paid < 0 || paid > total)) {
    return 'Paid amount must be between zero and the invoice sum.';
  }

  const isConsistent =
    (form.invoice_status === 'created' && (paid == null || paid === 0)) ||
    (form.invoice_status === 'prepaid' && isStrictPartial(total, paid)) ||
    (form.invoice_status === 'paid' && paid === total);

  return isConsistent ? null : PAYMENT_STATE_ERROR;
}
