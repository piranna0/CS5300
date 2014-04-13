<!DOCTYPE html>
<html>
<head><title>My Servlet</title></head>
<body>	

<h1>
${myMessage}
</h1>

<form method="post">
	<button type="submit" name="action" value="replace">Replace</button>
	<input type="text" name="message" maxlength="243"><br>
	<button type="submit" name="action" value="refresh">Refresh</button><br>
	<button type="submit" name="action" value="logout">Logout</button>
</form>

<!-- 
<p>
Cookie value: ${myVal}<br>
Location: ${myLocation}<br>
Expiration/discard times: ${myTimes}<br>
View: ${myView}
</p>
 -->

<table style="width:600px">
<tr>
	<td align="right">Cookie Value: </td>
	<td>${myVal}</td>
</tr>
<tr>
	<td align="right">Location: </td>
	<td>${myLocation}</td>
</tr>
<tr>
	<td align="right">Expiration time: </td>
	<td>${myExp}</td>
</tr>
<tr>
	<td align="right">Discard time: </td>
	<td>${myDis}</td>
</tr>
<tr>
	<td align="right">View: </td>
	<td>${myView}</td>
</tr>
</table>

</body></html>