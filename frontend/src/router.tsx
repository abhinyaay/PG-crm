import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Link, Outlet } from 'react-router-dom';
import type { Role } from '@/api/types';
import { FullPageSpinner, HomeRedirect, RequireAuth, RequireRole } from '@/auth/guards';
import { AppShell } from '@/components/layout/AppShell';
import { Spinner } from '@/components/ui/States';
import { RouteErrorBoundary } from '@/components/layout/RouteErrorBoundary';
import LoginPage from '@/features/auth/LoginPage';

// Feature pages are code-split; each feature folder owns its pages.
const OperatorDashboardPage = lazy(() => import('@/features/dashboard/OperatorDashboardPage'));
const StaffHomePage = lazy(() => import('@/features/dashboard/StaffHomePage'));
const PropertyListPage = lazy(() => import('@/features/properties/PropertyListPage'));
const PropertyDetailPage = lazy(() => import('@/features/properties/PropertyDetailPage'));
const PropertyFormPage = lazy(() => import('@/features/properties/PropertyFormPage'));
const BedBoardPage = lazy(() => import('@/features/rooms/BedBoardPage'));
const TenantListPage = lazy(() => import('@/features/tenants/TenantListPage'));
const TenantDetailPage = lazy(() => import('@/features/tenants/TenantDetailPage'));
const TenantFormPage = lazy(() => import('@/features/tenants/TenantFormPage'));
const BookingsPage = lazy(() => import('@/features/bookings/BookingsPage'));
const InvoiceListPage = lazy(() => import('@/features/billing/InvoiceListPage'));
const InvoiceDetailPage = lazy(() => import('@/features/billing/InvoiceDetailPage'));
const PaymentListPage = lazy(() => import('@/features/billing/PaymentListPage'));
const PaymentDetailPage = lazy(() => import('@/features/billing/PaymentDetailPage'));
const RecordPaymentPage = lazy(() => import('@/features/billing/RecordPaymentPage'));
const MaintenanceListPage = lazy(() => import('@/features/maintenance/MaintenanceListPage'));
const MaintenanceDetailPage = lazy(() => import('@/features/maintenance/MaintenanceDetailPage'));
const MaintenanceCreatePage = lazy(() => import('@/features/maintenance/MaintenanceCreatePage'));
const ComplaintListPage = lazy(() => import('@/features/complaints/ComplaintListPage'));
const ComplaintDetailPage = lazy(() => import('@/features/complaints/ComplaintDetailPage'));
const ComplaintCreatePage = lazy(() => import('@/features/complaints/ComplaintCreatePage'));
const NotificationsPage = lazy(() => import('@/features/notifications/NotificationsPage'));
const UsersPage = lazy(() => import('@/features/users/UsersPage'));
const AuditLogPage = lazy(() => import('@/features/audit/AuditLogPage'));
const AccountPage = lazy(() => import('@/features/account/AccountPage'));
const TenantHomePage = lazy(() => import('@/features/portal/TenantHomePage'));
const MyBillsPage = lazy(() => import('@/features/portal/MyBillsPage'));
const MyProfilePage = lazy(() => import('@/features/portal/MyProfilePage'));

const OPS: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN', 'MANAGER'];
const ADMINS: Role[] = ['SUPER_ADMIN', 'PROPERTY_ADMIN'];
const STAFFISH: Role[] = [...OPS, 'STAFF'];
const ALL: Role[] = [...STAFFISH, 'TENANT'];

function gate(roles: readonly Role[], el: ReactNode) {
  return <RequireRole roles={roles}>{el}</RequireRole>;
}

function NotFound() {
  return (
    <div className="py-16 text-center">
      <p className="text-5xl font-semibold text-slate-300 dark:text-slate-700">404</p>
      <p className="mt-2 text-slate-600 dark:text-slate-300">This page does not exist.</p>
      <Link to="/" className="mt-4 inline-block font-medium text-brand-700 hover:underline dark:text-brand-300">
        Go home
      </Link>
    </div>
  );
}

export const routes = [
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorBoundary /> },
  {
    path: '/',
    errorElement: <RouteErrorBoundary />,
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      {
        element: (
          <Suspense fallback={<Spinner />}>
            <Outlet />
          </Suspense>
        ),
        children: [
          { index: true, element: <HomeRedirect /> },
          { path: 'dashboard', element: gate(OPS, <OperatorDashboardPage />) },
          { path: 'work', element: gate(['STAFF'], <StaffHomePage />) },
          { path: 'properties', element: gate(STAFFISH, <PropertyListPage />) },
          { path: 'properties/new', element: gate(ADMINS, <PropertyFormPage />) },
          { path: 'properties/:id', element: gate(STAFFISH, <PropertyDetailPage />) },
          { path: 'properties/:id/edit', element: gate(ADMINS, <PropertyFormPage />) },
          { path: 'beds', element: gate(STAFFISH, <BedBoardPage />) },
          { path: 'tenants', element: gate(STAFFISH, <TenantListPage />) },
          { path: 'tenants/new', element: gate(OPS, <TenantFormPage />) },
          { path: 'tenants/:id', element: gate(STAFFISH, <TenantDetailPage />) },
          { path: 'tenants/:id/edit', element: gate(OPS, <TenantFormPage />) },
          { path: 'bookings', element: gate(OPS, <BookingsPage />) },
          { path: 'invoices', element: gate(OPS, <InvoiceListPage />) },
          { path: 'invoices/:id', element: gate(OPS, <InvoiceDetailPage />) },
          { path: 'payments', element: gate(OPS, <PaymentListPage />) },
          { path: 'payments/new', element: gate(OPS, <RecordPaymentPage />) },
          { path: 'payments/:id', element: gate(OPS, <PaymentDetailPage />) },
          { path: 'maintenance', element: gate(ALL, <MaintenanceListPage />) },
          { path: 'maintenance/new', element: gate(ALL, <MaintenanceCreatePage />) },
          { path: 'maintenance/:id', element: gate(ALL, <MaintenanceDetailPage />) },
          { path: 'complaints', element: gate(ALL, <ComplaintListPage />) },
          { path: 'complaints/new', element: gate(ALL, <ComplaintCreatePage />) },
          { path: 'complaints/:id', element: gate(ALL, <ComplaintDetailPage />) },
          { path: 'notifications', element: <NotificationsPage /> },
          { path: 'users', element: gate(ADMINS, <UsersPage />) },
          { path: 'audit', element: gate(ADMINS, <AuditLogPage />) },
          { path: 'account', element: <AccountPage /> },
          { path: 'me', element: gate(['TENANT'], <TenantHomePage />) },
          { path: 'me/bills', element: gate(['TENANT'], <MyBillsPage />) },
          { path: 'me/profile', element: gate(['TENANT'], <MyProfilePage />) },
          { path: '*', element: <NotFound /> },
        ],
      },
    ],
  },
];

export function createRouter() {
  return createBrowserRouter(routes, { future: { v7_relativeSplatPath: true } });
}

export { FullPageSpinner };
