import { Badge } from '@mantine/core';

// Map backend status strings to Mantine color names.
// Approved → teal (matches Figma #12b886), warning → yellow,
// rejected → red, initiated → gray.
const STATUS_COLOR = {
  initiated: 'gray',
  'sent for approval': 'yellow',
  approved: 'teal',
  rejected: 'red',
  created: 'gray',
  prepaid: 'yellow',
  paid: 'teal',
};

export default function StatusBadge({ status }) {
  return (
    <Badge color={STATUS_COLOR[status] || 'gray'} variant="filled" radius="xl">
      {status}
    </Badge>
  );
}
