Set-Location E:\cafe\BACKEND\smart-coffee-backend
.\mvnw.cmd -B -o clean compile 2>&1 | Out-File -FilePath _rbac_clean_compile.log -Encoding utf8
Write-Output "COMPILE_EXIT=$LASTEXITCODE"
Select-String -Path _rbac_clean_compile.log -Pattern "BUILD SUCCESS|BUILD FAILURE|\[ERROR\]" | Select-Object -Last 5 | ForEach-Object { $_.Line }
if ($LASTEXITCODE -eq 0) {
    .\mvnw.cmd -B -o test 2>&1 | Out-File -FilePath _rbac_full_test.log -Encoding utf8
    Write-Output "TEST_EXIT=$LASTEXITCODE"
    Select-String -Path _rbac_full_test.log -Pattern "Tests run:.*Failures|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]" | Select-Object -Last 8 | ForEach-Object { $_.Line }
}
if ($LASTEXITCODE -eq 0) {
    .\mvnw.cmd -B -o verify 2>&1 | Out-File -FilePath _rbac_full_verify.log -Encoding utf8
    Write-Output "VERIFY_EXIT=$LASTEXITCODE"
    Select-String -Path _rbac_full_verify.log -Pattern "Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|Tests run:.*in com\.|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]" | Select-Object -Last 10 | ForEach-Object { $_.Line }
}
