Set-Location E:\cafe\BACKEND\smart-coffee-backend
.\mvnw.cmd -B -o verify "-Dit.test=RbacHardeningMigrationIT,RoleAdministrationAccessIT,RbacPrivilegeLadderIT" "-Dtest=NoUnitTestsHere" "-Dsurefire.failIfNoSpecifiedTests=false" "-DfailIfNoTests=false" 2>&1 | Out-File -FilePath _rbac_targeted_it.log -Encoding utf8
Write-Output "EXIT=$LASTEXITCODE"
Select-String -Path _rbac_targeted_it.log -Pattern "Tests run:|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]" | Select-Object -Last 20 | ForEach-Object { $_.Line }
