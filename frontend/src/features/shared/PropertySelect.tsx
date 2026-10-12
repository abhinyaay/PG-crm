import { forwardRef, type SelectHTMLAttributes } from 'react';
import { Select } from '@/components/ui/Field';
import { usePropertyOptions } from './lookups';

type Props = Omit<SelectHTMLAttributes<HTMLSelectElement>, 'children'> & { label?: string; error?: string; allLabel?: string | null; containerClassName?: string };

/** Property dropdown; `allLabel` adds an "all properties" option with an empty value. */
export const PropertySelect = forwardRef<HTMLSelectElement, Props>(function PropertySelect({ label = 'Property', allLabel = 'All properties', ...rest }, ref) {
  const { data, isLoading } = usePropertyOptions();
  return (
    <Select ref={ref} label={label} disabled={isLoading || rest.disabled} {...rest}>
      {allLabel !== null && <option value="">{allLabel}</option>}
      {allLabel === null && <option value="">Select a property…</option>}
      {data?.map((p) => (
        <option key={p.id} value={p.id}>
          {p.name}
        </option>
      ))}
    </Select>
  );
});
