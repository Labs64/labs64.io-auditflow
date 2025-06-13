def transform(input_data):
    """
    Transforms the input JSON data structure, consolidating user and order information.
    This is the 'default' transformation.

    Example Input (for /transform/default):
    {
      "user_data": {
        "first_name": "John",
        "last_name": "Doe",
        "details": {
          "age": 30,
          "city": "New York"
        }
      },
      "order_info": {
        "order_id": "12345",
        "amount": 100.50
      }
    }

    Example Output:
    {
      "full_name": "John Doe",
      "age_of_user": 30,
      "user_location": "New York",
      "transaction_id": "12345"
    }
    """
    transformed_data = {}

    user_data = input_data.get('user_data', {})
    details = user_data.get('details', {})
    order_info = input_data.get('order_info', {})

    # Ensure to remove the 'transformation_type' if it somehow remains in the input,
    # as this transform is now triggered by the path.
    # It's good practice for the transformation functions to expect only the data.
    # input_data.pop('transformation_type', None) # No longer needed as it's not from body

    if user_data.get('first_name') and user_data.get('last_name'):
        transformed_data['full_name'] = f"{user_data['first_name']} {user_data['last_name']}"
    if details.get('age'):
        transformed_data['age_of_user'] = details['age']
    if details.get('city'):
        transformed_data['user_location'] = details['city']
    if order_info.get('order_id'):
        transformed_data['transaction_id'] = order_info['order_id']

    return transformed_data
