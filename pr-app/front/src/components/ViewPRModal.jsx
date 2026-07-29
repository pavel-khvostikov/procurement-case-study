import { useState } from 'react';
import {
  Button,
  Divider,
  Group,
  Modal,
  SimpleGrid,
  Stack,
  Text,
} from '@mantine/core';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import InvoiceStatusPanel from './InvoiceStatusPanel.jsx';
import StatusBadge from './StatusBadge.jsx';

const FieldRow = ({ label, value }) => (
  <Stack gap={2}>
    <Text size="xs" fw={500} c="dimmed">
      {label}
    </Text>
    <Text size="md" c="dark.7" style={{ whiteSpace: 'pre-wrap' }}>
      {value}
    </Text>
  </Stack>
);

export default function ViewPRModal({ pr, onClose, onUpdated }) {
  const { user } = useAuth();
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState(null);

  if (!pr) return null;

  // --- Role-aware action gating ---
  // Only the request author can send "initiated" → "sent for approval".
  // Only finance users can approve/reject when status is "sent for approval".
  // Once approved or rejected, no more transitions.
  const isAuthor = user?.username === pr.request_author;
  const isFinance = user?.role === 'finance';
  const canViewInvoices =
    isFinance || (pr.author_id != null && user?.id === pr.author_id);
  const status = pr.request_approval_status;

  const canSendForApproval = isAuthor && status === 'initiated';
  const canApproveOrReject = isFinance && status === 'sent for approval';

  const transition = async (newStatus) => {
    setError(null);
    setUpdating(true);
    try {
      await api.put(`/purchase-request/${pr.id}`, {
        request_approval_status: newStatus,
      });
      onUpdated?.();
      onClose();
    } catch (err) {
      setError(err?.response?.data?.detail || 'Could not update status');
    } finally {
      setUpdating(false);
    }
  };

  const exportPdf = () => {
    window.open(
      `${api.defaults.baseURL}/purchase-request/${pr.id}/pdf`,
      '_blank',
      'noopener'
    );
  };

  // Helper note above the action row — explains what the user can do.
  let note = null;
  if (canSendForApproval) {
    note = {
      title: 'You are the request author',
      body: 'You can send for approval when you are ready. Once finance approves it, you can send the PR PDF to the supplier.',
    };
  } else if (canApproveOrReject) {
    note = {
      title: 'Awaiting your approval',
      body: 'You are a finance reviewer. Approve to allow the requester to send the PR to the supplier. Rejecting returns the request to the author.',
    };
  }

  return (
    <Modal
      opened={!!pr}
      onClose={onClose}
      size="lg"
      centered
      title={
        <Group gap="sm">
          <Text fw={600} size="lg">
            {pr.request_code}
          </Text>
          <StatusBadge status={status} />
        </Group>
      }
    >
      <Stack gap="md">
        <FieldRow label="Request name" value={pr.request_name} />
        <SimpleGrid cols={2} spacing="md">
          <FieldRow label="Author" value={pr.request_author} />
          <FieldRow label="Code" value={pr.request_code} />
          <FieldRow label="Supplier" value={pr.supplier_name} />
          <FieldRow label="Supplier email" value={pr.supplier_email} />
        </SimpleGrid>
        <FieldRow label="Request details" value={pr.request_details} />

        {canViewInvoices && (
          <>
            <Divider />
            <InvoiceStatusPanel purchaseRequestId={pr.id} />
          </>
        )}

        {note && (
          <Stack bg="gray.0" p="sm" gap={2} style={{ borderRadius: 4 }}>
            <Text size="xs" fw={600} c="dimmed">
              {note.title}
            </Text>
            <Text size="xs" c="dimmed">
              {note.body}
            </Text>
          </Stack>
        )}

        {error && (
          <Text c="red" size="sm">
            {error}
          </Text>
        )}

        <Divider />

        <Group justify="space-between">
          <Button variant="default" onClick={exportPdf}>
            PDF
          </Button>
          <Group gap="sm">
            {canSendForApproval && (
              <Button
                color="teal"
                onClick={() => transition('sent for approval')}
                loading={updating}
              >
                Send for approval
              </Button>
            )}
            {canApproveOrReject && (
              <>
                <Button
                  color="red"
                  variant="outline"
                  onClick={() => transition('rejected')}
                  loading={updating}
                >
                  Reject
                </Button>
                <Button
                  color="teal"
                  onClick={() => transition('approved')}
                  loading={updating}
                >
                  Approve
                </Button>
              </>
            )}
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
