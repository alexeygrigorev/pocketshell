package com.pocketshell.app.jobs

import com.pocketshell.app.jobs.RecurringJobsViewModel.DefaultRecurringJobsSshConnector
import com.pocketshell.app.jobs.RecurringJobsViewModel.RecurringJobsSshConnector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecurringJobsModule {
    @Binds
    abstract fun bindRecurringJobsSshConnector(
        connector: DefaultRecurringJobsSshConnector,
    ): RecurringJobsSshConnector
}
