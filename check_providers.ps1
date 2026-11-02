# Enhanced Provider Checker for CloudX-V2
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "      Memeriksa Status Seluruh Provider CloudX-V2        " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Coba ambil Website.json jarak jauh (jika ada akses internet)
$remoteJson = $null
$remoteUrl = "https://raw.githubusercontent.com/Asm0d3usX/CloudX/builds/Website.json"
try {
    $wc = New-Object System.Net.WebClient
    $wc.Headers.Add("User-Agent", "Mozilla/5.0")
    $jsonStr = $wc.DownloadString($remoteUrl)
    $remoteJson = $jsonStr | ConvertFrom-Json
    Write-Host "[OK] Berhasil mengunduh Website.json terbaru dari GitHub.`n" -ForegroundColor Green
} catch {
    Write-Host "[INFO] Tidak dapat mengunduh Website.json remote (menggunakan URL lokal dari .kt).`n" -ForegroundColor DarkGray
}

# 2. Dapatkan daftar provider dari source code
$providers = Get-ChildItem -Path . -Recurse -File -Filter "*.kt" |
    Where-Object { $_.FullName -notmatch "Extractors" -and $_.FullName -notmatch "Plugin" } |
    ForEach-Object {
        $content = Get-Content $_.FullName -Raw
        if ($content -match 'override\s+var\s+mainUrl\s*=\s*"([^"]+)"') {
            $localUrl = $matches[1]
            $name = $_.Directory.Name
            if ($name -match 'com$') { $name = $_.Directory.Parent.Name }
            if ($content -match 'override\s+var\s+name\s*=\s*"([^"]+)"') {
                $name = $matches[1]
            }
            $jsonKey = ""
            if ($content -match 'optJSONArray\("([^"]+)"\)') {
                $jsonKey = $matches[1]
            }

            $activeUrl = $localUrl
            $remoteTarget = "-"
            if ($remoteJson -and $jsonKey -and $remoteJson.$jsonKey) {
                $remoteTarget = $remoteJson.$jsonKey[0]
                if ($remoteTarget) {
                    $activeUrl = $remoteTarget
                }
            }

            [PSCustomObject]@{
                Name = $name
                File = $_.FullName.Replace((Get-Location).Path + "\", "")
                LocalUrl = $localUrl
                JsonKey = $jsonKey
                RemoteUrl = $remoteTarget
                CheckUrl = $activeUrl
            }
        }
    }

function Test-UrlStatus {
    param([string]$url)
    
    if ([string]::IsNullOrWhiteSpace($url) -or $url -eq "-") {
        return @{ Status = "NO_URL"; Code = 0; Redirect = "-"; Detail = "URL tidak ditemukan" }
    }

    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Method = "GET"
        $req.Timeout = 7000
        $req.ReadWriteTimeout = 7000
        $req.AllowAutoRedirect = $false
        $req.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        $req.Headers.Add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")

        $resp = [System.Net.HttpWebResponse]$req.GetResponse()
        $statusCode = [int]$resp.StatusCode
        $statusDesc = $resp.StatusDescription
        $resp.Close()

        return @{
            Status = "ACTIVE"
            Code = $statusCode
            Redirect = "-"
            Detail = "Aktif (HTTP $statusCode $statusDesc)"
        }
    }
    catch [System.Net.WebException] {
        if ($_.Response) {
            $resp = [System.Net.HttpWebResponse]$_.Response
            $code = [int]$resp.StatusCode
            $loc = $resp.Headers["Location"]
            $resp.Close()

            if ($code -in @(301, 302, 307, 308)) {
                return @{
                    Status = "REDIRECT"
                    Code = $code
                    Redirect = $loc
                    Detail = "Pindah alamat (Redirect -> $loc)"
                }
            }
            elseif ($code -eq 403) {
                return @{
                    Status = "CLOUDFLARE_PROTECTED"
                    Code = $code
                    Redirect = "-"
                    Detail = "Domain aktif tapi terhalang Cloudflare/Bot Guard (403)"
                }
            }
            else {
                return @{
                    Status = "HTTP_ERROR"
                    Code = $code
                    Redirect = "-"
                    Detail = "HTTP Error $code"
                }
            }
        }
        else {
            $msg = $_.Exception.Message
            if ($msg -match "timed out") {
                return @{ Status = "TIMEOUT"; Code = 0; Redirect = "-"; Detail = "Timeout (Tidak merespon)" }
            }
            elseif ($msg -match "could not be resolved|remote name could not be resolved") {
                return @{ Status = "DNS_DEAD"; Code = 0; Redirect = "-"; Detail = "DNS Mati / Domain tidak ada (NXDOMAIN)" }
            }
            elseif ($msg -match "connection was closed|forcibly closed") {
                return @{ Status = "CONN_RESET"; Code = 0; Redirect = "-"; Detail = "Koneksi diputus server (Reset/Blocked)" }
            }
            else {
                return @{ Status = "OFFLINE"; Code = 0; Redirect = "-"; Detail = $msg }
            }
        }
    }
    catch {
        return @{ Status = "ERROR"; Code = 0; Redirect = "-"; Detail = $_.Exception.Message }
    }
}

$results = @()
foreach ($p in $providers) {
    Write-Host "Mengecek [$($p.Name)] ($($p.CheckUrl))... " -NoNewline
    $res = Test-UrlStatus -url $p.CheckUrl

    if ($res.Status -eq "ACTIVE") {
        Write-Host "AKTIF" -ForegroundColor Green
    }
    elseif ($res.Status -eq "REDIRECT") {
        Write-Host "PINDAH -> $($res.Redirect)" -ForegroundColor Yellow
    }
    elseif ($res.Status -eq "CLOUDFLARE_PROTECTED") {
        Write-Host "PROTEKSI (403)" -ForegroundColor DarkYellow
    }
    else {
        Write-Host "MATI ($($res.Detail))" -ForegroundColor Red
    }

    $results += [PSCustomObject]@{
        Provider   = $p.Name
        "URL Diuji" = $p.CheckUrl
        "Status"    = $res.Status
        "Kode"      = $res.Code
        "Pindah Ke" = $res.Redirect
        "Keterangan" = $res.Detail
    }
}

Write-Host "`n================================ HASIL ================================" -ForegroundColor Cyan
$results | Format-Table Provider, "URL Diuji", Status, "Pindah Ke", Keterangan -AutoSize
