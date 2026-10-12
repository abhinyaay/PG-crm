import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RoomResponse } from '@/api/types';
import { BedBoard, BedLegend, groupRoomsByFloor } from './BedBoard';

const room = (over: Partial<RoomResponse>): RoomResponse => ({
  id: 1,
  propertyId: 1,
  floorId: 1,
  floorNumber: 1,
  buildingCode: 'A',
  roomNumber: '101',
  roomType: 'DOUBLE',
  capacity: 2,
  monthlyRent: 11000,
  securityDeposit: 22000,
  airConditioned: true,
  attachedBathroom: false,
  status: 'ACTIVE',
  beds: [],
  ...over,
});

const rooms: RoomResponse[] = [
  room({ id: 3, roomNumber: '201', floorNumber: 2, beds: [{ id: 30, bedCode: 'A', status: 'MAINTENANCE' }] }),
  room({
    id: 1,
    roomNumber: '101',
    beds: [
      { id: 11, bedCode: 'B', status: 'OCCUPIED' },
      { id: 10, bedCode: 'A', status: 'AVAILABLE' },
    ],
  }),
  room({ id: 2, roomNumber: '102', roomType: 'SINGLE', airConditioned: false, beds: [{ id: 20, bedCode: 'A', status: 'RESERVED' }] }),
];

describe('<BedBoard>', () => {
  it('groups rooms by block and floor, sorted', () => {
    const groups = groupRoomsByFloor(rooms);
    expect(groups.map((g) => g.title)).toEqual(['Block A · Floor 1', 'Block A · Floor 2']);
    expect(groups[0]!.rooms.map((r) => r.roomNumber)).toEqual(['101', '102']);
  });

  it('renders rooms as cards and beds as status chips with accessible labels', () => {
    render(<BedBoard rooms={rooms} onBedClick={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Block A · Floor 1' })).toBeInTheDocument();
    const r101 = screen.getByTestId('room-101');
    expect(within(r101).getByText('Room 101')).toBeInTheDocument();
    expect(within(r101).getByText('1/2 free')).toBeInTheDocument();
    const chips = within(r101).getAllByRole('button', { name: /bed/i });
    // beds sorted by code
    expect(chips.map((c) => c.getAttribute('aria-label'))).toEqual(['Room 101, bed A: Available', 'Room 101, bed B: Occupied']);
    expect(screen.getByRole('button', { name: 'Room 102, bed A: Reserved' })).toHaveAttribute('data-status', 'RESERVED');
    expect(screen.getByRole('button', { name: 'Room 201, bed A: Maintenance' })).toHaveClass('bg-stripes');
  });

  it('calls onBedClick with the room and bed', async () => {
    const onBedClick = vi.fn();
    render(<BedBoard rooms={rooms} onBedClick={onBedClick} />);
    await userEvent.click(screen.getByRole('button', { name: 'Room 102, bed A: Reserved' }));
    expect(onBedClick).toHaveBeenCalledWith(expect.objectContaining({ roomNumber: '102' }), { id: 20, bedCode: 'A', status: 'RESERVED' });
  });

  it('filters rooms by bed status', () => {
    render(<BedBoard rooms={rooms} statusFilter="AVAILABLE" onBedClick={vi.fn()} />);
    expect(screen.getByTestId('room-101')).toBeInTheDocument();
    expect(screen.queryByTestId('room-102')).not.toBeInTheDocument();
    expect(screen.queryByTestId('room-201')).not.toBeInTheDocument();
  });

  it('shows an empty message when nothing matches', () => {
    render(<BedBoard rooms={[]} />);
    expect(screen.getByText('No rooms match.')).toBeInTheDocument();
  });

  it('only offers room editing when a handler is provided', () => {
    const { rerender } = render(<BedBoard rooms={rooms} />);
    expect(screen.queryByRole('button', { name: /edit room/i })).not.toBeInTheDocument();
    rerender(<BedBoard rooms={rooms} onEditRoom={vi.fn()} />);
    expect(screen.getAllByRole('button', { name: /edit room/i })).toHaveLength(3);
  });

  it('legend shows counts per status', () => {
    render(<BedLegend counts={{ AVAILABLE: 3, OCCUPIED: 27 }} />);
    expect(screen.getByText(/Available/).parentElement ?? screen.getByText(/Available/)).toHaveTextContent('Available· 3');
  });
});
