import { useRef, useState } from 'react';
import {
  Button,
  FileButton,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { api } from '../api.js';
import { usePurchaseRequests } from '../hooks/usePurchaseRequests.js';
import {
  paymentStateError,
  updatePaymentField,
  updatePaymentStatus,
} from '../paymentForm.js';
import PurchaseRequestSelect, {
  SupplierMismatchWarning,
} from './PurchaseRequestSelect.jsx';

const EMPTY = {
  invoice_number: '',
  supplier: '',
  purchase_request_number: '',
  invoice_sum: '',
  invoice_sum_paid: '',
  invoice_status: 'created',
};

export default function UploadInvoiceModal({ opened, onClose, onCreated }) {
  const [form, setForm] = useState(EMPTY);
  const [file, setFile] = useState(null);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const resetRef = useRef(null);
  const {
    requests,
    status: purchaseRequestStatus,
    error: purchaseRequestError,
    retry: retryPurchaseRequests,
  } = usePurchaseRequests(opened);
  const selectedPurchaseRequest = requests.find(
    (request) => request.request_code === form.purchase_request_number
  );
  const paymentError = paymentStateError(form);
  const showPaymentError =
    form.invoice_sum !== '' ||
    form.invoice_sum_paid !== '' ||
    form.invoice_status !== 'created';

  // Read value synchronously before scheduling the state update — React 19
  // + StrictMode invokes functional updaters twice and `e.currentTarget`
  // is nulled out between calls.
  const setField = (k) => (e) => {
    const value = typeof e === 'string' || typeof e === 'number'
      ? e
      : e?.currentTarget?.value ?? '';
    setForm((current) => updatePaymentField(current, k, value));
  };

  const setPaymentStatus = (status) => {
    setForm((current) => updatePaymentStatus(current, status));
  };

  const close = () => {
    setForm(EMPTY);
    setFile(null);
    setError(null);
    resetRef.current?.();
    onClose();
  };

  const selectPurchaseRequest = (code, request) => {
    if (!code || !request) return;
    setForm((current) => ({
      ...current,
      purchase_request_number: code,
      supplier: request.supplier_name,
    }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const fd = new FormData();
      fd.append('invoice_number', form.invoice_number);
      fd.append('supplier', form.supplier);
      fd.append('purchase_request_number', form.purchase_request_number);
      fd.append('invoice_sum', form.invoice_sum);
      if (form.invoice_sum_paid !== '' && form.invoice_sum_paid != null)
        fd.append('invoice_sum_paid', form.invoice_sum_paid);
      fd.append('invoice_status', form.invoice_status);
      if (file) fd.append('attachment', file);

      await api.post('/invoice', fd);
      onCreated?.();
      close();
    } catch (err) {
      setError(err?.response?.data?.message || 'Could not upload invoice');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={<Text fw={600} size="lg">Upload Invoice</Text>}
      size="lg"
      centered
    >
      <form onSubmit={submit}>
        <Stack gap="md">
          {/* PDF drop zone (clickable Mantine FileButton with a styled label) */}
          <FileButton
            resetRef={resetRef}
            accept="application/pdf"
            onChange={setFile}
          >
            {(props) => (
              <Stack
                {...props}
                bg="gray.0"
                p="lg"
                gap={4}
                align="center"
                style={{
                  borderRadius: 6,
                  border: '1px dashed var(--mantine-color-gray-4)',
                  cursor: 'pointer',
                }}
              >
                <Text size="xl">📄</Text>
                <Text size="sm" fw={500} c="dark.6">
                  {file ? file.name : 'Click to choose invoice PDF'}
                </Text>
                <Text size="xs" c="dimmed">PDF only · max 10 MB</Text>
              </Stack>
            )}
          </FileButton>

          <TextInput
            label="Invoice number"
            placeholder="e.g. INV-2026-0512"
            required
            value={form.invoice_number}
            onChange={setField('invoice_number')}
          />
          <Group grow align="flex-start">
            <TextInput
              label="Supplier"
              placeholder="Apple Store B2B"
              required
              value={form.supplier}
              onChange={setField('supplier')}
            />
            <PurchaseRequestSelect
              requests={requests}
              status={purchaseRequestStatus}
              error={purchaseRequestError}
              value={form.purchase_request_number}
              onChange={selectPurchaseRequest}
              onRetry={retryPurchaseRequests}
              required
            />
          </Group>
          <SupplierMismatchWarning
            invoiceSupplier={form.supplier}
            purchaseRequest={selectedPurchaseRequest}
          />
          <Group grow align="flex-start">
            <NumberInput
              label="Invoice sum"
              placeholder="11400.00"
              required
              min={0.01}
              decimalScale={2}
              fixedDecimalScale
              value={form.invoice_sum}
              onChange={setField('invoice_sum')}
            />
            <NumberInput
              label="Paid so far"
              placeholder="0.00"
              min={0}
              max={form.invoice_sum || undefined}
              required={form.invoice_status === 'prepaid'}
              description={
                form.invoice_status === 'paid'
                  ? 'Paid invoices use the full invoice sum.'
                  : form.invoice_status === 'created'
                    ? 'Created invoices must have no payment.'
                    : 'Enter a partial payment below the invoice sum.'
              }
              decimalScale={2}
              fixedDecimalScale
              value={form.invoice_sum_paid}
              onChange={setField('invoice_sum_paid')}
              error={showPaymentError ? paymentError : null}
            />
          </Group>

          <Stack gap={6}>
            <Text size="sm" fw={500} c="dark.7">Initial status</Text>
            <SegmentedControl
              fullWidth
              data={['created', 'prepaid', 'paid']}
              value={form.invoice_status}
              onChange={setPaymentStatus}
            />
          </Stack>

          {error && <Text c="red" size="sm">{error}</Text>}

          <Group justify="flex-end">
            <Button variant="default" onClick={close} disabled={submitting}>
              Cancel
            </Button>
            <Button
              type="submit"
              loading={submitting}
              disabled={
                purchaseRequestStatus !== 'success' ||
                requests.length === 0 ||
                !selectedPurchaseRequest ||
                Boolean(paymentError)
              }
            >
              Upload invoice
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
