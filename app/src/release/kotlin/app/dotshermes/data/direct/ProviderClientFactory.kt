package app.dotshermes.data.direct

/** Les versions publiées utilisent uniquement les origines HTTPS officielles. */
fun createProviderClient(): ProviderClient = HttpProviderClient()
