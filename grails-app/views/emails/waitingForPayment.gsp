<%@ page contentType="text/html"%>

Hello,<br />
<br />
Your order #${order.getId()} is awaiting for payment. As soon as your payment will be received, the order will be processed.<br />
<br />
Your order details:<br />
<br />
<g:render template="/emails/orderTable"/>
<br />
Thank you.<br />