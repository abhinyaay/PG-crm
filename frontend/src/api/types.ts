/**
 * DTOs mirroring docs/openapi.json (backend) and analytics-service/README.md.
 * Dates: `IsoDate` = "YYYY-MM-DD" (business date), `IsoDateTime` = UTC instant.
 */
export type IsoDate = string;
export type IsoDateTime = string;
export type Money = number;

export const ROLES = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER', 'STAFF', 'TENANT'] as const;
export type Role = (typeof ROLES)[number];

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface FieldError {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

export interface ErrorEnvelope {
  timestamp?: string;
  status: number;
  error?: string;
  code: string;
  message: string;
  path?: string;
  correlationId?: string;
  fieldErrors?: FieldError[];
}

// ---------- auth ----------
export interface UserInfo {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  organizationId: number | null;
}

export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: IsoDateTime;
  refreshToken: string;
  refreshTokenExpiresAt: IsoDateTime;
  user: UserInfo;
}

// ---------- users ----------
export type UserStatus = 'ACTIVE' | 'INACTIVE';
export interface UserResponse {
  id: number;
  organizationId?: number;
  email: string;
  fullName: string;
  phone?: string;
  role: Role;
  status: UserStatus;
  lastLoginAt?: IsoDateTime;
  createdAt?: IsoDateTime;
  propertyIds?: number[];
}
export interface CreateUserRequest {
  email: string;
  password: string;
  fullName: string;
  phone?: string;
  role: Role;
  organizationId?: number;
  propertyIds?: number[];
}

// ---------- organizations ----------
export interface OrganizationResponse {
  id: number;
  name: string;
  slug: string;
  contactEmail?: string;
  contactPhone?: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt?: IsoDateTime;
}

// ---------- properties ----------
export type PropertyType = 'MEN' | 'WOMEN' | 'CO_LIVING';
export type PropertyStatus = 'ACTIVE' | 'INACTIVE' | 'UNDER_RENOVATION';
export interface Address {
  line1: string;
  line2?: string;
  city: string;
  state: string;
  postalCode: string;
  country?: string;
}
export interface PropertyResponse {
  id: number;
  organizationId: number;
  code: string;
  name: string;
  description?: string;
  type: PropertyType;
  status: PropertyStatus;
  address: Address;
  contactPhone?: string;
  rentDueDay: number;
  createdAt?: IsoDateTime;
  updatedAt?: IsoDateTime;
}
export interface PropertyRequest {
  code?: string;
  name: string;
  description?: string;
  type: PropertyType;
  address: Address;
  contactPhone?: string;
  rentDueDay?: number;
  organizationId?: number;
}
export interface BuildingResponse {
  id: number;
  propertyId: number;
  code: string;
  name: string;
}
export interface FloorResponse {
  id: number;
  buildingId: number;
  floorNumber: number;
  label?: string;
}
export interface AssignmentResponse {
  userId: number;
  fullName: string;
  email: string;
  role: Role;
  assignedAt: IsoDateTime;
}
export interface OccupancyResponse {
  propertyId: number;
  totalBeds: number;
  occupiedBeds: number;
  reservedBeds: number;
  availableBeds: number;
  maintenanceBeds: number;
  occupancyRate: number;
}

// ---------- rooms & beds ----------
export const ROOM_TYPES = ['SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD', 'DORMITORY'] as const;
export type RoomType = (typeof ROOM_TYPES)[number];
export const BED_STATUSES = ['AVAILABLE', 'OCCUPIED', 'RESERVED', 'MAINTENANCE'] as const;
export type BedStatus = (typeof BED_STATUSES)[number];
export interface BedSummary {
  id: number;
  bedCode: string;
  status: BedStatus;
}
export interface RoomResponse {
  id: number;
  propertyId: number;
  floorId: number;
  floorNumber: number;
  buildingCode: string;
  roomNumber: string;
  roomType: RoomType;
  capacity: number;
  monthlyRent: Money;
  securityDeposit: Money;
  airConditioned: boolean;
  attachedBathroom: boolean;
  status: 'ACTIVE' | 'INACTIVE';
  beds: BedSummary[];
}
export interface RoomRequest {
  floorId?: number;
  roomNumber?: string;
  roomType: RoomType;
  capacity: number;
  monthlyRent: Money;
  securityDeposit: Money;
  airConditioned: boolean;
  attachedBathroom: boolean;
}
export interface BedResponse {
  id: number;
  propertyId: number;
  roomId: number;
  roomNumber: string;
  bedCode: string;
  status: BedStatus;
  roomType: RoomType;
  monthlyRent: Money;
  securityDeposit: Money;
  airConditioned: boolean;
  attachedBathroom: boolean;
}

// ---------- tenants ----------
export type TenantStatus = 'PROSPECTIVE' | 'ACTIVE' | 'ON_NOTICE' | 'MOVED_OUT';
export type Gender = 'MALE' | 'FEMALE' | 'OTHER';
export interface EmergencyContact {
  name: string;
  relationship: string;
  phone: string;
  email?: string;
  primary?: boolean;
}
export interface TenantResponse {
  id: number;
  organizationId: number;
  propertyId: number;
  userId?: number;
  fullName: string;
  email?: string;
  phone: string;
  gender?: Gender;
  dateOfBirth?: IsoDate;
  occupation?: string;
  organizationName?: string;
  permanentAddress?: string;
  status: TenantStatus;
  joiningDate?: IsoDate;
  moveOutDate?: IsoDate;
  emergencyContacts?: EmergencyContact[];
  createdAt?: IsoDateTime;
}
export interface TenantSummary {
  id: number;
  propertyId: number;
  fullName: string;
  email?: string;
  phone: string;
  status: TenantStatus;
  joiningDate?: IsoDate;
  moveOutDate?: IsoDate;
}
export interface TenantRequest {
  propertyId?: number;
  fullName: string;
  email?: string;
  phone: string;
  gender?: Gender;
  dateOfBirth?: IsoDate;
  occupation?: string;
  organizationName?: string;
  permanentAddress?: string;
  emergencyContacts?: EmergencyContact[];
}
export const DOCUMENT_TYPES = ['AADHAAR', 'PASSPORT', 'DRIVING_LICENSE', 'PAN', 'VOTER_ID', 'OTHER'] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export type VerificationStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';
export interface DocumentResponse {
  id: number;
  documentType: DocumentType;
  documentNumberMasked: string;
  storageKey?: string;
  verificationStatus: VerificationStatus;
  verifiedAt?: IsoDateTime;
  createdAt?: IsoDateTime;
}

// ---------- bookings & allocations ----------
export type BookingStatus = 'RESERVED' | 'CHECKED_IN' | 'CANCELLED' | 'EXPIRED';
export interface BookingResponse {
  id: number;
  bookingReference: string;
  propertyId: number;
  tenantId: number;
  bedId: number;
  status: BookingStatus;
  expectedCheckInDate: IsoDate;
  reservedUntil: IsoDateTime;
  notes?: string;
  cancelledAt?: IsoDateTime;
  cancellationReason?: string;
  createdAt?: IsoDateTime;
}
export type AllocationStatus = 'ACTIVE' | 'ENDED' | 'TRANSFERRED';
export interface AllocationResponse {
  id: number;
  propertyId: number;
  tenantId: number;
  bedId: number;
  bookingId?: number;
  previousAllocationId?: number;
  status: AllocationStatus;
  startDate: IsoDate;
  endDate?: IsoDate;
  noticeDate?: IsoDate;
  expectedEndDate?: IsoDate;
  endReason?: 'CHECK_OUT' | 'TRANSFER';
  monthlyRent: Money;
  securityDeposit: Money;
}
export interface InvoiceSummary {
  id: number;
  invoiceNumber: string;
  type: InvoiceType;
  totalAmount: Money;
  dueDate: IsoDate;
  status: InvoiceStatus;
}
export interface CheckInResponse {
  allocation: AllocationResponse;
  invoices: InvoiceSummary[];
}
export interface Deduction {
  description: string;
  amount: Money;
}
export interface SettlementSummary {
  depositHeld: Money;
  outstandingDues: Money;
  deductions: Money;
  refundDue: Money;
  amountPayableByTenant: Money;
  deductionItems: Deduction[];
}
export interface CheckOutResponse {
  allocation: AllocationResponse;
  settlement: SettlementSummary;
}
export interface TransferResponse {
  previous: AllocationResponse;
  current: AllocationResponse;
}

// ---------- billing ----------
export type InvoiceType = 'RENT' | 'SECURITY_DEPOSIT';
export const INVOICE_STATUSES = ['ISSUED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'VOID'] as const;
export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];
export const LINE_ITEM_TYPES = ['RENT', 'SECURITY_DEPOSIT', 'ELECTRICITY', 'FOOD', 'LAUNDRY', 'LATE_FEE', 'OTHER', 'DISCOUNT'] as const;
export type LineItemType = (typeof LINE_ITEM_TYPES)[number];
export interface LineItem {
  id: number;
  type: LineItemType;
  description: string;
  amount: Money;
  createdAt?: IsoDateTime;
}
export interface InvoiceResponse {
  id: number;
  invoiceNumber: string;
  propertyId: number;
  tenantId: number;
  allocationId?: number;
  type: InvoiceType;
  billingMonth?: IsoDate;
  periodStart?: IsoDate;
  periodEnd?: IsoDate;
  issueDate: IsoDate;
  dueDate: IsoDate;
  status: InvoiceStatus;
  subtotal: Money;
  discountTotal: Money;
  totalAmount: Money;
  amountPaid: Money;
  balance: Money;
  currency: string;
  lineItems?: LineItem[];
}
export const PAYMENT_METHODS = ['CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE'] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];
export type PaymentStatus = 'COMPLETED' | 'PARTIALLY_REFUNDED' | 'REFUNDED';
export interface PaymentResponse {
  id: number;
  receiptNumber: string;
  propertyId: number;
  tenantId: number;
  invoiceId: number;
  amount: Money;
  refundedAmount: Money;
  method: PaymentMethod;
  status: PaymentStatus;
  externalReference?: string;
  paidAt: IsoDateTime;
  notes?: string;
}
export interface RecordPaymentRequest {
  invoiceId: number;
  amount: Money;
  method: PaymentMethod;
  externalReference?: string;
  paidAt?: IsoDateTime;
  notes?: string;
}
export interface RefundResponse {
  id: number;
  refundReference: string;
  paymentId: number;
  amount: Money;
  reason: string;
  refundedAt: IsoDateTime;
}
export interface TenantBalanceResponse {
  tenantId: number;
  outstanding: Money;
  depositHeld: Money;
  openInvoices: InvoiceResponse[];
}

// ---------- maintenance ----------
export const MAINTENANCE_CATEGORIES = ['ELECTRICAL', 'PLUMBING', 'CARPENTRY', 'CLEANING', 'APPLIANCE', 'INTERNET', 'PEST_CONTROL', 'OTHER'] as const;
export type MaintenanceCategory = (typeof MAINTENANCE_CATEGORIES)[number];
export const PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type Priority = (typeof PRIORITIES)[number];
export const MAINTENANCE_STATUSES = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'] as const;
export type MaintenanceStatus = (typeof MAINTENANCE_STATUSES)[number];
export interface MaintenanceResponse {
  id: number;
  ticketNumber: string;
  propertyId: number;
  roomId?: number;
  tenantId?: number;
  raisedByUserId?: number;
  category: MaintenanceCategory;
  priority: Priority;
  title: string;
  description: string;
  status: MaintenanceStatus;
  assignedToUserId?: number;
  assignedAt?: IsoDateTime;
  startedAt?: IsoDateTime;
  resolvedAt?: IsoDateTime;
  closedAt?: IsoDateTime;
  resolutionNotes?: string;
  slaDueAt?: IsoDateTime;
  slaBreached: boolean;
  createdAt: IsoDateTime;
}
export interface MaintenanceHistoryEntry {
  fromStatus?: MaintenanceStatus;
  toStatus: MaintenanceStatus;
  changedBy?: number;
  note?: string;
  changedAt: IsoDateTime;
}

// ---------- complaints ----------
export const COMPLAINT_CATEGORIES = ['NOISE', 'CLEANLINESS', 'FOOD', 'STAFF_BEHAVIOUR', 'SECURITY', 'BILLING', 'ROOMMATE', 'OTHER'] as const;
export type ComplaintCategory = (typeof COMPLAINT_CATEGORIES)[number];
export const COMPLAINT_STATUSES = ['OPEN', 'IN_PROGRESS', 'ESCALATED', 'RESOLVED', 'CLOSED'] as const;
export type ComplaintStatus = (typeof COMPLAINT_STATUSES)[number];
export interface ComplaintResponse {
  id: number;
  complaintNumber: string;
  propertyId: number;
  tenantId?: number;
  raisedByUserId?: number;
  category: ComplaintCategory;
  priority: Priority;
  subject: string;
  description: string;
  status: ComplaintStatus;
  assignedToUserId?: number;
  escalationLevel: number;
  escalatedAt?: IsoDateTime;
  dueAt?: IsoDateTime;
  resolution?: string;
  resolvedAt?: IsoDateTime;
  closedAt?: IsoDateTime;
  createdAt: IsoDateTime;
}
export interface CommentResponse {
  id: number;
  authorUserId: number;
  body: string;
  internal: boolean;
  createdAt: IsoDateTime;
}
export interface ComplaintActivity {
  type: 'CREATED' | 'ASSIGNED' | 'STATUS_CHANGED' | 'ESCALATED' | 'COMMENTED';
  fromStatus?: ComplaintStatus;
  toStatus?: ComplaintStatus;
  actorUserId?: number;
  details?: string;
  createdAt: IsoDateTime;
}

// ---------- notifications ----------
export interface NotificationResponse {
  id: number;
  type: string;
  title: string;
  body: string;
  referenceType?: string;
  referenceId?: number;
  readAt?: IsoDateTime;
  createdAt: IsoDateTime;
}

// ---------- audit ----------
export interface AuditLogResponse {
  id: number;
  organizationId?: number;
  actorUserId?: number;
  actorEmail?: string;
  action: string;
  entityType: string;
  entityId?: number;
  details?: Record<string, unknown>;
  ipAddress?: string;
  correlationId?: string;
  createdAt: IsoDateTime;
}

// ---------- dashboard ----------
export interface BedStats {
  total: number;
  occupied: number;
  available: number;
  reserved: number;
  maintenance: number;
  occupancyRate: number;
}
export interface UpcomingCheckout {
  allocationId: number;
  tenantId: number;
  tenantName: string;
  propertyId: number;
  roomNumber: string;
  bedCode: string;
  expectedEndDate: IsoDate;
}
export interface RecentPayment {
  paymentId: number;
  receiptNumber: string;
  tenantId: number;
  tenantName: string;
  amount: Money;
  method: PaymentMethod;
  paidAt: IsoDateTime;
}
export interface OperationsDashboard {
  scope: string;
  activeTenants: number;
  beds: BedStats;
  monthlyRevenue: Money;
  outstandingRent: Money;
  overdueInvoices: number;
  pendingMaintenance: number;
  slaBreachedMaintenance: number;
  openComplaints: number;
  escalatedComplaints: number;
  upcomingCheckouts: UpcomingCheckout[];
  recentPayments: RecentPayment[];
  generatedAt: IsoDateTime;
}
export interface TicketSummary {
  id: number;
  number: string;
  title: string;
  priority: Priority;
  status: string;
  dueAt?: IsoDateTime;
  breached: boolean;
}
export interface StaffDashboard {
  assignedOpen: number;
  inProgress: number;
  slaBreached: number;
  assignedComplaints: number;
  nextTickets: TicketSummary[];
  generatedAt: IsoDateTime;
}
export interface TenantDashboard {
  tenantId: number;
  status: TenantStatus;
  propertyName?: string;
  roomNumber?: string;
  bedCode?: string;
  stayingSince?: IsoDate;
  monthlyRent?: Money;
  outstanding: Money;
  depositHeld?: Money;
  nextDueDate?: IsoDate;
  openMaintenanceRequests: number;
  openComplaints: number;
  recentPayments: RecentPayment[];
  generatedAt: IsoDateTime;
}

// ---------- analytics ----------
export interface AnalyticsMeta {
  generatedAt: IsoDateTime;
  timezone: string;
  organizationId: number | null;
  propertyIds: number[] | null;
  fromMonth: string;
  toMonth: string;
}
export interface OccupancyReport {
  meta: AnalyticsMeta;
  snapshot: {
    totalBeds: number;
    occupiedBeds: number;
    reservedBeds: number;
    availableBeds: number;
    maintenanceBeds: number;
    occupancyRatePct: number | null;
    rawOccupancyRatePct: number | null;
    vacancyRatePct: number | null;
  };
  byProperty: { propertyId: number; propertyName: string; totalBeds: number; occupiedBeds: number; occupancyRatePct: number | null }[];
  byRoomType: { roomType: string; rooms: number; totalBeds: number; occupiedBeds: number; utilizationPct: number | null }[];
  monthlyTrend: {
    month: string;
    daysCounted: number;
    occupiedBedDays: number;
    availableBedDays: number;
    occupancyRatePct: number | null;
    averageOccupiedBeds: number | null;
  }[];
}
export interface RevenueReport {
  meta: AnalyticsMeta;
  totals: {
    netRentRevenue: number;
    grossRentCollected: number;
    depositsCollected: number;
    totalRefunds: number;
    billedRent: number;
    billedRentCollected: number;
    collectionRatePct: number | null;
    averageMonthlyNetRentRevenue: number | null;
  };
  monthly: {
    month: string;
    grossRentCollected: number;
    rentRefunds: number;
    netRentRevenue: number;
    depositsCollected: number;
    billedRent: number;
    billedRentCollected: number;
    collectionRatePct: number | null;
    momGrowthPct: number | null;
  }[];
  byMethod: { method: string; paymentCount: number; netRentRevenue: number; shareOfRentRevenuePct: number | null }[];
}
export interface PaymentsReport {
  meta: AnalyticsMeta;
  summary: {
    outstandingTotal: number;
    overdueTotal: number;
    openInvoiceCount: number;
    collectionRatePct: number | null;
    defaultRatePct: number | null;
    avgDaysLate: number | null;
  };
  aging: { bucket: string; label: string; invoiceCount: number; outstandingAmount: number; sharePct: number | null }[];
  outstandingByProperty: { propertyId: number; propertyName: string; invoiceCount: number; outstandingAmount: number; overdueAmount: number }[];
}
export interface PropertyComparisonRow {
  rank: number;
  propertyId: number;
  propertyCode: string;
  propertyName: string;
  city: string;
  status: string;
  totalBeds: number;
  occupiedBeds: number;
  occupancyRatePct: number | null;
  activeTenants: number;
  currentMonthRevenue: number;
  revenuePerBed: number | null;
  collectionRatePct: number | null;
  outstandingAmount: number;
  openMaintenance: number;
  slaCompliancePct: number | null;
  openComplaints: number;
}
export interface PropertiesReport {
  meta: AnalyticsMeta;
  currentMonth: string;
  sortBy: string;
  order: 'asc' | 'desc';
  properties: PropertyComparisonRow[];
}
