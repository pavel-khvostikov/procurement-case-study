import { Card, Group, Stack, Text } from '@mantine/core';

const fmt = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(n);

const cents = (n) => Math.round(Number(n || 0) * 100);

const isThisMonth = (iso) => {
  if (!iso) return false;
  const d = new Date(iso);
  const now = new Date();
  return d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth();
};

function StatCard({ label, value, valueColor }) {
  return (
    <Card withBorder radius="md" padding="lg" style={{ flex: 1 }}>
      <Stack gap={6}>
        <Text size="xs" fw={500} c="dimmed">{label}</Text>
        <Text size="xl" fw={600} c={valueColor || 'dark.7'}>{value}</Text>
      </Stack>
    </Card>
  );
}

// Computed entirely on the client from the loaded invoice list. The backend
// could (and should, eventually) expose a /invoice/summary endpoint, but for
// six invoices it's fine.
export default function SummaryCards({ invoices }) {
  const total = invoices.length;
  const outstanding = invoices
    .reduce(
      (acc, i) => acc + Math.max(cents(i.invoice_sum) - cents(i.invoice_sum_paid), 0),
      0
    );
  const prepaid = invoices
    .filter((i) => {
      const total = cents(i.invoice_sum);
      const paid = cents(i.invoice_sum_paid);
      return paid > 0 && paid < total;
    })
    .reduce((acc, i) => acc + cents(i.invoice_sum_paid), 0);
  const paidThisMonth = invoices
    .filter((i) => i.invoice_status === 'paid' && isThisMonth(i.updated_at))
    .reduce((acc, i) => acc + cents(i.invoice_sum_paid), 0);

  return (
    <Group gap="md" grow>
      <StatCard label="Total invoices" value={String(total)} />
      <StatCard label="Outstanding" value={fmt(outstanding / 100)} valueColor="red" />
      <StatCard label="Prepaid (partial)" value={fmt(prepaid / 100)} valueColor="yellow.7" />
      <StatCard label="Paid this month" value={fmt(paidThisMonth / 100)} valueColor="teal" />
    </Group>
  );
}
