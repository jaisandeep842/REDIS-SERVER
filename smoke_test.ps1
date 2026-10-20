$client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 6380)
$stream = $client.GetStream()
$writer = New-Object System.IO.StreamWriter($stream, [System.Text.Encoding]::UTF8)
$reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
$writer.AutoFlush = $true

function Query($cmd) {
    Write-Host "Sending: $cmd"
    $writer.Write("$cmd`r`n")
    Start-Sleep -Milliseconds 100
    while ($stream.DataAvailable) {
        $line = $reader.ReadLine()
        Write-Host "Received: $line"
    }
}

Query "PING"
Query "SET greeting 'Hello from PowerShell'"
Query "GET greeting"
Query "HSET user:1 name Alice role engineer"
Query "HGETALL user:1"
Query "QUIT"

$client.Close()
