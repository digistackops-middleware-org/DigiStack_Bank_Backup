psql -U digistack_app -d digistack_bank \
  -f /home/wasadmin/digistack-bank/database/migrations/V5__update_customer1_email.sql


  verify
  -------

  SELECT id, username, email FROM users WHERE username = 'customer1';