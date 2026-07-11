BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Migrate.psm1') -Force
}

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
