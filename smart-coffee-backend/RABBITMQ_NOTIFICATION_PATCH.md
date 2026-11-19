# RabbitMQ notification topology
Declare durable queue `smartcoffee.notification`, bind it to the business topic exchange for `order.ready.v1`, `payment.completed.v1`, `payment.failed.v1`, and `loyalty.*`. Configure dead-letter exchange and bounded retry. Event payloads must include a recipient `userId` or a recipient-resolution key.
