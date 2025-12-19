"""
NetLicensing Sink - Process checkout transactions and create/update NetLicensing entities.

This sink receives Labs64 IO Ecosystem checkout transaction events via AuditFlow notifications
and performs NetLicensing Licensee and License creation/update operations.

Expected event structure (from checkout module):
{
    "eventType": "checkout.transaction.completed",
    "sourceSystem": "checkout",
    "tenantId": "T123456",
    "extra": {
        "transaction": {
            "id": "uuid",
            "status": "COMPLETED",
            "paymentMethod": "STRIPE",
            "billingInfo": {...},
            "purchaseOrder": {
                "id": "uuid",
                "currency": "EUR",
                "customer": {
                    "id": "uuid",
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john.doe@example.com"
                },
                "items": [
                    {
                        "name": "Product License",
                        "sku": "PROD-001",
                        "quantity": 1,
                        "price": 9900,
                        "extra": {
                            "productNumber": "P123",
                            "licenseTemplateNumber": "LT456"
                        }
                    }
                ]
            }
        }
    }
}
"""
import logging
import requests
import json
import time
from typing import Optional
from urllib.parse import urljoin

logger = logging.getLogger(__name__)

# NetLicensing API constants
NETLICENSING_BASE_URL = "https://go.netlicensing.io/core/v2/rest/"
USER_AGENT = "Labs64-AuditFlow-NetLicensingSink/1.0"


def process(event_data: dict, properties: dict) -> dict:
    """
    Process a checkout transaction event by creating/updating NetLicensing entities.

    Args:
        event_data: The audit event data containing checkout transaction details
        properties: Configuration properties
            - api-key: NetLicensing API key (required)
            - base-url: NetLicensing API base URL (default: https://go.netlicensing.io/core/v2/rest/)
            - product-number: Default product number if not in event (optional)
            - license-template-number: Default license template if not in event (optional)
            - quantity-to-licensee: Create new licensee per quantity (default: false)
            - mark-for-transfer: Mark licensees for transfer (default: true)
            - save-transaction-data: Store transaction data in licensee (default: true)
            - timeout: Request timeout in seconds (default: 30)
            - retry-count: Number of retries (default: 3)

    Returns:
        dict: Processing result with created/updated entity information
    """
    # Validate required properties
    api_key = properties.get('api-key')
    if not api_key:
        raise ValueError("Missing required property: 'api-key'")

    # Get configuration
    base_url = properties.get('base-url', NETLICENSING_BASE_URL)
    if not base_url.endswith('/'):
        base_url += '/'

    default_product_number = properties.get('product-number')
    default_license_template = properties.get('license-template-number')
    quantity_to_licensee = properties.get('quantity-to-licensee', 'false').lower() == 'true'
    mark_for_transfer = properties.get('mark-for-transfer', 'true').lower() == 'true'
    save_transaction_data = properties.get('save-transaction-data', 'true').lower() == 'true'
    timeout = int(properties.get('timeout', '30'))
    retry_count = int(properties.get('retry-count', '3'))

    # Validate event type
    event_type = event_data.get('eventType', '')
    if not event_type.startswith('checkout.transaction'):
        logger.warning(f"Skipping non-checkout event: {event_type}")
        return {
            "processed": False,
            "reason": f"Event type '{event_type}' is not a checkout transaction event",
            "destination": "netlicensing"
        }

    # Extract transaction data from event
    extra = event_data.get('extra', {})
    transaction = extra.get('transaction', {})

    if not transaction:
        raise ValueError("Missing 'transaction' in event extra data")

    # Only process completed transactions
    status = transaction.get('status', '')
    if status != 'COMPLETED':
        logger.info(f"Skipping transaction with status: {status}")
        return {
            "processed": False,
            "reason": f"Transaction status '{status}' is not COMPLETED",
            "destination": "netlicensing"
        }

    # Create NetLicensing client
    client = NetLicensingClient(
        api_key=api_key,
        base_url=base_url,
        timeout=timeout,
        retry_count=retry_count
    )

    # Extract purchase order and customer
    purchase_order = transaction.get('purchaseOrder', {})
    customer = purchase_order.get('customer', {})
    items = purchase_order.get('items', [])
    billing_info = transaction.get('billingInfo', {})

    if not items:
        raise ValueError("Purchase order has no items")

    # Process each item and create licenses
    created_licensees = []
    created_licenses = []
    errors = []

    for item in items:
        try:
            result = _process_item(
                client=client,
                item=item,
                customer=customer,
                billing_info=billing_info,
                transaction=transaction,
                purchase_order=purchase_order,
                default_product_number=default_product_number,
                default_license_template=default_license_template,
                quantity_to_licensee=quantity_to_licensee,
                mark_for_transfer=mark_for_transfer,
                save_transaction_data=save_transaction_data
            )
            created_licensees.extend(result.get('licensees', []))
            created_licenses.extend(result.get('licenses', []))
        except Exception as e:
            logger.error(f"Failed to process item {item.get('sku', 'unknown')}: {e}")
            errors.append({
                "item_sku": item.get('sku'),
                "item_name": item.get('name'),
                "error": str(e)
            })

    if errors and not created_licensees:
        raise RuntimeError(f"Failed to process all items: {errors}")

    return {
        "processed": True,
        "destination": "netlicensing",
        "transaction_id": transaction.get('id'),
        "licensees_created": len(set(created_licensees)),
        "licensee_numbers": list(set(created_licensees)),
        "licenses_created": len(created_licenses),
        "license_numbers": created_licenses,
        "errors": errors if errors else None
    }


def _process_item(
    client: 'NetLicensingClient',
    item: dict,
    customer: dict,
    billing_info: dict,
    transaction: dict,
    purchase_order: dict,
    default_product_number: Optional[str],
    default_license_template: Optional[str],
    quantity_to_licensee: bool,
    mark_for_transfer: bool,
    save_transaction_data: bool
) -> dict:
    """Process a single purchase order item and create licensee/licenses."""

    item_extra = item.get('extra', {})

    # Get product and license template numbers
    product_number = item_extra.get('productNumber') or default_product_number
    license_template_number = item_extra.get('licenseTemplateNumber') or default_license_template

    if not product_number:
        raise ValueError(f"No product number for item: {item.get('name')}")

    if not license_template_number:
        raise ValueError(f"No license template number for item: {item.get('name')}")

    # Get existing licensee if specified
    existing_licensee_number = item_extra.get('licenseeNumber')
    quantity = item.get('quantity', 1)

    created_licensees = []
    created_licenses = []

    licensee = None

    # Try to get existing licensee
    if existing_licensee_number and not quantity_to_licensee:
        try:
            licensee = client.get_licensee(existing_licensee_number)
            # Verify licensee belongs to the same product
            if licensee and licensee.get('product', {}).get('number') != product_number:
                logger.warning(f"Licensee {existing_licensee_number} belongs to different product, creating new")
                licensee = None
        except Exception as e:
            logger.warning(f"Could not retrieve licensee {existing_licensee_number}: {e}")
            licensee = None

    # Create licenses for the quantity
    for i in range(quantity):
        # Create new licensee if needed
        if licensee is None or quantity_to_licensee:
            licensee = _create_licensee(
                client=client,
                product_number=product_number,
                customer=customer,
                billing_info=billing_info,
                transaction=transaction,
                purchase_order=purchase_order,
                mark_for_transfer=mark_for_transfer,
                save_transaction_data=save_transaction_data
            )
            created_licensees.append(licensee['number'])

        # Create license
        license_data = _create_license(
            client=client,
            licensee_number=licensee['number'],
            license_template_number=license_template_number,
            item=item
        )
        created_licenses.append(license_data['number'])

        # Only create new licensees for subsequent quantities if quantity_to_licensee
        if not quantity_to_licensee:
            pass  # Reuse same licensee for all quantities

    return {
        "licensees": created_licensees,
        "licenses": created_licenses
    }


def _create_licensee(
    client: 'NetLicensingClient',
    product_number: str,
    customer: dict,
    billing_info: dict,
    transaction: dict,
    purchase_order: dict,
    mark_for_transfer: bool,
    save_transaction_data: bool
) -> dict:
    """Create a new licensee in NetLicensing."""

    # Build licensee name from customer info
    first_name = customer.get('firstName') or billing_info.get('firstName', '')
    last_name = customer.get('lastName') or billing_info.get('lastName', '')
    email = customer.get('email') or billing_info.get('email', '')

    licensee_name = f"{first_name} {last_name}".strip() or email or "Unknown Customer"

    properties = {
        'active': 'true',
        'name': licensee_name
    }

    if mark_for_transfer:
        properties['markedForTransfer'] = 'true'

    # Add customer info as custom properties
    if email:
        properties['email'] = email
    if customer.get('id'):
        properties['customerId'] = str(customer['id'])
    if customer.get('phone'):
        properties['phone'] = customer['phone']

    # Store transaction data if enabled
    if save_transaction_data:
        transaction_data = {
            'transactionId': str(transaction.get('id', '')),
            'purchaseOrderId': str(purchase_order.get('id', '')),
            'paymentMethod': transaction.get('paymentMethod', ''),
            'currency': purchase_order.get('currency', ''),
            'billingCountry': billing_info.get('country', ''),
            'billingCity': billing_info.get('city', '')
        }
        properties['checkoutData'] = json.dumps(transaction_data)

    return client.create_licensee(product_number, properties)


def _create_license(
    client: 'NetLicensingClient',
    licensee_number: str,
    license_template_number: str,
    item: dict
) -> dict:
    """Create a new license in NetLicensing."""

    properties = {
        'active': 'true'
    }

    # Get license template to check type
    try:
        license_template = client.get_license_template(license_template_number)
        license_type = license_template.get('licenseType', '')

        # Set start date for time-volume licenses
        if license_type == 'TIMEVOLUME':
            properties['startDate'] = 'now'
    except Exception as e:
        logger.warning(f"Could not retrieve license template {license_template_number}: {e}")

    # Add item-specific properties from extra
    item_extra = item.get('extra', {})
    for key in ['startDate', 'endDate', 'maxSessions', 'maxCheckouts']:
        if key in item_extra:
            properties[key] = str(item_extra[key])

    return client.create_license(licensee_number, license_template_number, properties)


class NetLicensingClient:
    """HTTP client for NetLicensing API."""

    def __init__(self, api_key: str, base_url: str, timeout: int = 30, retry_count: int = 3):
        self.api_key = api_key
        self.base_url = base_url
        self.timeout = timeout
        self.retry_count = retry_count
        self.session = requests.Session()
        self.session.headers.update({
            'Accept': 'application/json',
            'Content-Type': 'application/x-www-form-urlencoded',
            'User-Agent': USER_AGENT,
            'Authorization': f'Basic {self._encode_api_key()}'
        })

    def _encode_api_key(self) -> str:
        """Encode API key for Basic auth (apiKey:)."""
        import base64
        credentials = f"apiKey:{self.api_key}"
        return base64.b64encode(credentials.encode()).decode()

    def _request(self, method: str, endpoint: str, data: Optional[dict] = None) -> dict:
        """Make HTTP request with retry logic."""
        url = urljoin(self.base_url, endpoint)
        last_error = None

        for attempt in range(self.retry_count):
            try:
                logger.debug(f"NetLicensing API request: {method} {url} (attempt {attempt + 1})")

                response = self.session.request(
                    method=method,
                    url=url,
                    data=data,
                    timeout=self.timeout
                )

                response.raise_for_status()

                result = response.json()

                # Extract items from NetLicensing response format
                if 'items' in result and 'item' in result['items']:
                    items = result['items']['item']
                    if isinstance(items, list) and len(items) > 0:
                        return self._parse_item(items[0])
                    elif isinstance(items, dict):
                        return self._parse_item(items)

                return result

            except requests.exceptions.RequestException as e:
                last_error = e
                logger.warning(f"NetLicensing API attempt {attempt + 1} failed: {e}")
                if hasattr(e, 'response') and e.response is not None:
                    logger.warning(f"Response body: {e.response.text[:500]}")
                if attempt < self.retry_count - 1:
                    time.sleep(2 ** attempt)
                continue

        raise RuntimeError(f"NetLicensing API request failed after {self.retry_count} attempts: {last_error}")

    def _parse_item(self, item: dict) -> dict:
        """Parse NetLicensing item response into flat dictionary."""
        result = {}

        if 'type' in item:
            result['type'] = item['type']

        if 'property' in item:
            for prop in item['property']:
                name = prop.get('name')
                value = prop.get('value')
                if name:
                    result[name] = value

        # Handle nested items (e.g., product in licensee)
        if 'list' in item:
            for nested_list in item['list']:
                list_name = nested_list.get('name')
                if list_name and 'property' in nested_list:
                    nested_props = {}
                    for prop in nested_list['property']:
                        name = prop.get('name')
                        value = prop.get('value')
                        if name:
                            nested_props[name] = value
                    result[list_name] = nested_props

        return result

    def get_licensee(self, licensee_number: str) -> dict:
        """Get licensee by number."""
        return self._request('GET', f'licensee/{licensee_number}')

    def create_licensee(self, product_number: str, properties: dict) -> dict:
        """Create a new licensee."""
        data = {'productNumber': product_number}
        data.update(properties)
        return self._request('POST', 'licensee', data)

    def get_license_template(self, license_template_number: str) -> dict:
        """Get license template by number."""
        return self._request('GET', f'licensetemplate/{license_template_number}')

    def create_license(self, licensee_number: str, license_template_number: str, properties: dict) -> dict:
        """Create a new license."""
        data = {'licenseeNumber': licensee_number, 'licenseTemplateNumber': license_template_number}
        data.update(properties)
        return self._request('POST', 'license', data)
