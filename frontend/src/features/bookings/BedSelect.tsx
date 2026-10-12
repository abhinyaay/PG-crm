import { Select } from '@/components/ui/Field';
import { formatMoney, humanize } from '@/lib/format';
import { useAvailableBeds } from '@/features/rooms/api';

export function BedSelect({ propertyId, value, onChange, error, label = 'Bed', excludeBedId }: { propertyId?: number; value: string; onChange: (v: string) => void; error?: string; label?: string; excludeBedId?: number }) {
  const beds = useAvailableBeds({ propertyId });
  const rows = (beds.data ?? []).filter((b) => b.id !== excludeBedId);
  return (
    <Select label={label} required value={value} onChange={(e) => onChange(e.target.value)} disabled={!propertyId || beds.isLoading} error={error} hint={beds.data && rows.length === 0 ? 'No available beds in this property' : undefined}>
      <option value="">{beds.isLoading ? 'Loading beds…' : 'Select an available bed…'}</option>
      {rows.map((b) => (
        <option key={b.id} value={b.id}>
          Room {b.roomNumber} · Bed {b.bedCode} — {humanize(b.roomType)}
          {b.airConditioned ? ', AC' : ''} — {formatMoney(b.monthlyRent)}/mo
        </option>
      ))}
    </Select>
  );
}
