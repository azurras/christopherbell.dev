Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Migrate.psm1') -Force

Describe 'MongoDB migration inventory' {
    It 'accepts identical collection counts and indexes' {
        $inventory = '{"accounts":{"count":20,"indexes":[{"name":"_id_","key":{"_id":1},"unique":false}]}}'
        { Assert-MongoInventoryEqual $inventory $inventory } | Should -Not -Throw
    }

    It 'rejects a missing target document' {
        { Assert-MongoInventoryEqual '{"accounts":{"count":20}}' '{"accounts":{"count":19}}' } | Should -Throw '*inventory mismatch*'
    }

    It 'rejects changed index uniqueness' {
        { Assert-MongoInventoryEqual '{"indexes":[{"unique":true}]}' '{"indexes":[{"unique":false}]}' } | Should -Throw '*inventory mismatch*'
    }
}

Describe 'WSL migration privilege boundaries' {
    InModuleScope Production.Migrate {
        It 'provides a root command boundary for MongoDB service control' {
            Get-Command Invoke-WslRootCommand -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }

        It 'invokes MongoDB service commands as WSL root' {
            Mock Invoke-CheckedProcess {}
            $config = [pscustomobject]@{ wslDistro='Debian'; repositoryPath='A:\repo' }
            Invoke-WslRootCommand $config 'systemctl stop mongod'
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'wsl.exe' -and
                ($ArgumentList -join ' ') -eq '-d Debian -u root -- bash -lc systemctl stop mongod'
            }
        }

        It 'keeps website commands under the normal WSL user' {
            Mock Invoke-CheckedProcess {}
            $config = [pscustomobject]@{ wslDistro='Debian'; repositoryPath='A:\repo' }
            Invoke-WslCommand $config 'pkill -f java'
            Should -Invoke Invoke-CheckedProcess -ParameterFilter { $ArgumentList -notcontains 'root' }
        }
    }
}

Describe 'WSL mongodump argument compatibility' {
    InModuleScope Production.Migrate {
        It 'provides WSL-compatible equals-form mongodump arguments' {
            Get-Command Get-WslMongoDumpArguments -ErrorAction SilentlyContinue | Should -Not -BeNullOrEmpty
        }

        It 'keeps URI database and archive values attached to their options' {
            $arguments = Get-WslMongoDumpArguments '/mnt/a/backups/prod.archive.gz'
            $arguments | Should -Contain '--uri=mongodb://127.0.0.1:27017'
            $arguments | Should -Contain '--db=christopherbell'
            $arguments | Should -Contain '--archive=/mnt/a/backups/prod.archive.gz'
            $arguments | Should -Not -Contain '--uri'
            $arguments | Should -Not -Contain '--archive'
        }
    }
}
