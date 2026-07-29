import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import StatusBadge from './StatusBadge.jsx';
import SummaryCards from './SummaryCards.jsx';
import UploadInvoiceModal from './UploadInvoiceModal.jsx';
import EditInvoiceModal from './EditInvoiceModal.jsx';

const fmt = (n) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(n || 0));

export default function InvoiceList() {
  const { logout } = useAuth();
  const [invoices, setInvoices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [purchaseRequestFilter, setPurchaseRequestFilter] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const loadRequestId = useRef(0);

  const load = useCallback(async () => {
    const currentRequestId = ++loadRequestId.current;
    setLoading(true);
    setLoadError(null);
    setInvoices([]);
    try {
      const response = await api.get('/invoice', {
        params: purchaseRequestFilter
          ? { purchase_request_number: purchaseRequestFilter }
          : undefined,
      });
      if (loadRequestId.current === currentRequestId) {
        setInvoices(response.data);
      }
    } catch (error) {
      if (loadRequestId.current === currentRequestId) {
        setLoadError(
          error?.response?.data?.message || 'Could not load invoices.'
        );
      }
    } finally {
      if (loadRequestId.current === currentRequestId) {
        setLoading(false);
      }
    }
  }, [purchaseRequestFilter]);

  useEffect(() => {
    load();
  }, [load]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return invoices;
    return invoices.filter((inv) =>
      [inv.invoice_number, inv.supplier, inv.purchase_request_number, inv.uploaded_by]
        .some((s) => (s || '').toLowerCase().includes(q))
    );
  }, [invoices, query]);

  const downloadUrl = (id) => `${api.defaults.baseURL}/invoice/${id}/attachment`;

  return (
    <Box bg="white" p={10} mih="100vh">
      {/* Header */}
      <Group
        justify="space-between"
        align="center"
        bg="rgba(34, 139, 230, 0.1)"
        p="md"
      >
        <Title order={3} c="dimmed">
          Invoices
        </Title>
        <Group gap="sm">
          <Button color="teal" variant="light" onClick={() => setUploadOpen(true)}>
            + Upload Invoice
          </Button>
          <Button color="red" variant="outline" onClick={logout}>
            Logout
          </Button>
        </Group>
      </Group>

      {/* Body */}
      <Box bg="gray.0" px="xl" py="lg" mih="calc(100vh - 96px)">
        <Stack gap="md">
          <TextInput
            placeholder="Search by invoice #, supplier, PR..."
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
            w={360}
          />

          {purchaseRequestFilter && (
            <Alert color="violet" title="Purchase request filter">
              <Group justify="space-between" align="center">
                <Text size="sm">
                  Showing validated invoices linked to{' '}
                  <strong>{purchaseRequestFilter}</strong>.
                </Text>
                <Button
                  type="button"
                  size="xs"
                  variant="light"
                  color="violet"
                  onClick={() => setPurchaseRequestFilter(null)}
                >
                  Clear filter
                </Button>
              </Group>
            </Alert>
          )}

          {loadError && (
            <Alert color="red" title="Invoices unavailable">
              <Group justify="space-between" align="center">
                <Text size="sm">{loadError}</Text>
                <Button
                  type="button"
                  size="xs"
                  variant="light"
                  color="red"
                  onClick={load}
                >
                  Retry
                </Button>
              </Group>
            </Alert>
          )}

          <SummaryCards invoices={invoices} />

          <Card withBorder p={0} radius="md" style={{ overflow: 'hidden' }}>
            <Table verticalSpacing="md" horizontalSpacing="md" striped="even">
              <Table.Thead bg="gray.0">
                <Table.Tr>
                  <Table.Th c="dimmed" style={{ width: 160 }}>Invoice #</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 280 }}>Supplier</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 100 }}>PR #</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 140 }}>Sum</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 140 }}>Paid</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 140 }}>Status</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 220 }}>Attachment</Table.Th>
                  <Table.Th c="dimmed" style={{ width: 100 }}>Actions</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {loading && (
                  <Table.Tr>
                    <Table.Td colSpan={8}>
                      <Center p="xl">
                        <Loader size="sm" />
                      </Center>
                    </Table.Td>
                  </Table.Tr>
                )}
                {!loading && !loadError && filtered.length === 0 && (
                  <Table.Tr>
                    <Table.Td colSpan={8}>
                      <Center p="xl">
                        <Text c="dimmed">
                          {query.trim()
                            ? purchaseRequestFilter
                              ? `No invoices linked to ${purchaseRequestFilter} match the current search.`
                              : 'No invoices match the current search.'
                            : purchaseRequestFilter
                              ? `No validated invoices are linked to ${purchaseRequestFilter}.`
                              : 'No invoices yet — upload one to get started.'}
                        </Text>
                      </Center>
                    </Table.Td>
                  </Table.Tr>
                )}
                {!loading &&
                  !loadError &&
                  filtered.map((inv) => (
                    <Table.Tr key={inv.id}>
                      <Table.Td fw={500}>{inv.invoice_number}</Table.Td>
                      <Table.Td>{inv.supplier}</Table.Td>
                      <Table.Td>
                        {inv.purchase_request_number && inv.purchase_request_validated_at ? (
                          <Anchor
                            component="button"
                            type="button"
                            fw={500}
                            c="violet"
                            onClick={() =>
                              setPurchaseRequestFilter(inv.purchase_request_number)
                            }
                          >
                            {inv.purchase_request_number}
                          </Anchor>
                        ) : inv.purchase_request_number ? (
                          <Stack gap={3} align="flex-start">
                            <Text size="sm" fw={500}>{inv.purchase_request_number}</Text>
                            <Badge color="yellow" variant="light" size="xs">
                              unverified
                            </Badge>
                          </Stack>
                        ) : (
                          '—'
                        )}
                      </Table.Td>
                      <Table.Td fw={500}>{fmt(inv.invoice_sum)}</Table.Td>
                      <Table.Td>{fmt(inv.invoice_sum_paid)}</Table.Td>
                      <Table.Td>
                        <StatusBadge status={inv.invoice_status} />
                      </Table.Td>
                      <Table.Td>
                        {inv.attachment_filename ? (
                          <Anchor
                            href={downloadUrl(inv.id)}
                            target="_blank"
                            rel="noopener"
                            size="sm"
                            c="violet"
                          >
                            📎 {inv.attachment_filename}
                          </Anchor>
                        ) : (
                          <Text size="sm" c="dimmed">no file</Text>
                        )}
                      </Table.Td>
                      <Table.Td>
                        <Button
                          variant="light"
                          color="gray"
                          size="xs"
                          onClick={() => setEditing(inv)}
                        >
                          Edit
                        </Button>
                      </Table.Td>
                    </Table.Tr>
                  ))}
              </Table.Tbody>
            </Table>
          </Card>
        </Stack>
      </Box>

      <UploadInvoiceModal
        opened={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onCreated={load}
      />
      <EditInvoiceModal
        invoice={editing}
        onClose={() => setEditing(null)}
        onUpdated={load}
      />
    </Box>
  );
}
