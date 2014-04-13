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

<p>
${myVal}
</p>

</body></html>