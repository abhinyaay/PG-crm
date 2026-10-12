import pytest

from app.core.authorization import resolve_scope
from app.core.errors import ForbiddenError
from app.core.security import Role
from tests.conftest import principal
from tests.fakes import FakeAccessRepository

ACCESS = FakeAccessRepository({5: [1, 2], 6: [7]})


def test_super_admin_scopes():
    p = principal(Role.SUPER_ADMIN, org=None)
    s = resolve_scope(p, organization_id=None, property_id=None, access=ACCESS)
    assert (s.organization_id, s.property_ids) == (None, None)
    s = resolve_scope(p, organization_id=20, property_id=7, access=ACCESS)
    assert (s.organization_id, s.property_ids) == (20, (7,))


def test_property_admin_scopes():
    p = principal(Role.PROPERTY_ADMIN, org=10)
    assert resolve_scope(p, organization_id=None, property_id=None, access=ACCESS).organization_id == 10
    assert resolve_scope(p, organization_id=10, property_id=2, access=ACCESS).property_ids == (2,)
    with pytest.raises(ForbiddenError):
        resolve_scope(p, organization_id=20, property_id=None, access=ACCESS)
    with pytest.raises(ForbiddenError):
        resolve_scope(p, organization_id=None, property_id=7, access=ACCESS)


def test_manager_scopes_intersect_with_own_org():
    p = principal(Role.MANAGER, org=10, user_id=5)
    assert resolve_scope(p, organization_id=None, property_id=None, access=ACCESS).property_ids == (1, 2)
    with pytest.raises(ForbiddenError):
        resolve_scope(p, organization_id=None, property_id=3, access=ACCESS)
    # a manager assigned to a property of another organization does not get it
    other = principal(Role.MANAGER, org=10, user_id=6)
    assert resolve_scope(other, organization_id=None, property_id=None, access=ACCESS).property_ids == ()


@pytest.mark.parametrize("role", [Role.STAFF, Role.TENANT])
def test_other_roles_forbidden(role):
    with pytest.raises(ForbiddenError):
        resolve_scope(principal(role), organization_id=None, property_id=None, access=ACCESS)


def test_manager_without_org_forbidden():
    with pytest.raises(ForbiddenError):
        resolve_scope(
            principal(Role.MANAGER, org=None), organization_id=None, property_id=None, access=ACCESS
        )
