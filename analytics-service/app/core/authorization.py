"""Turns an authenticated principal plus optional filters into an :class:`AnalyticsScope`.

Rules:
* SUPER_ADMIN    - everything; may narrow with ``organizationId`` and/or ``propertyId``.
* PROPERTY_ADMIN - own organization only; ``propertyId`` must belong to it.
* MANAGER        - only properties assigned in ``analytics.user_property_access`` (looked up on every
                   request, intersected with the manager's organization); ``propertyId`` must be one
                   of them.
* Non super-admins passing an ``organizationId`` other than their own get 403.
"""

from __future__ import annotations

from typing import Protocol

from app.core.errors import ForbiddenError
from app.core.scope import AnalyticsScope
from app.core.security import Principal, Role, ensure_analytics_role


class PropertyAccessLookup(Protocol):
    def manager_property_ids(self, user_id: int, organization_id: int) -> list[int]: ...
    def property_in_organization(self, property_id: int, organization_id: int) -> bool: ...


def resolve_scope(
    principal: Principal,
    *,
    organization_id: int | None,
    property_id: int | None,
    access: PropertyAccessLookup,
) -> AnalyticsScope:
    ensure_analytics_role(principal)

    if principal.role is Role.SUPER_ADMIN:
        return AnalyticsScope(
            organization_id=organization_id,
            property_ids=(property_id,) if property_id is not None else None,
        )

    own_org = principal.organization_id
    if own_org is None:
        raise ForbiddenError("Your account is not linked to an organization")
    if organization_id is not None and organization_id != own_org:
        raise ForbiddenError("You do not have access to this organization")

    if principal.role is Role.PROPERTY_ADMIN:
        if property_id is not None and not access.property_in_organization(property_id, own_org):
            raise ForbiddenError("You do not have access to this property")
        return AnalyticsScope(
            organization_id=own_org,
            property_ids=(property_id,) if property_id is not None else None,
        )

    # MANAGER
    assigned = access.manager_property_ids(principal.user_id, own_org)
    if property_id is not None:
        if property_id not in assigned:
            raise ForbiddenError("You do not have access to this property")
        return AnalyticsScope(organization_id=own_org, property_ids=(property_id,))
    return AnalyticsScope(organization_id=own_org, property_ids=tuple(assigned))
