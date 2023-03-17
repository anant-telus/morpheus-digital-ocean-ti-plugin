package com.morpheusdata.digitalocean.provisioning

import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.cloud.DigitalOceanCloudProvider
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.HostType
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.provisioning.UsersConfiguration
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.HostResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity

@Slf4j
class DigitalOceanProvisionProvider extends AbstractProvisionProvider {
	DigitalOceanPlugin plugin
	MorpheusContext context
	private static final String DIGITAL_OCEAN_ENDPOINT = 'https://api.digitalocean.com'
	private static final String UBUNTU_VIRTUAL_IMAGE_CODE = 'doplugin.image.os.digital-ocean-plugin.ubuntu.18.04'
	DigitalOceanApiService apiService

	DigitalOceanProvisionProvider(DigitalOceanPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.context = context
		apiService = new DigitalOceanApiService()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		VirtualImage virtualImage = new VirtualImage(
				code: UBUNTU_VIRTUAL_IMAGE_CODE,
				category:'doplugin.image.os.digital-ocean-plugin',
				name:'Ubuntu 18.04 LTS (Digital Ocean Marketplace)',
				imageType: ImageType.qcow2,
				systemImage:true,
				isCloudInit:true,
				externalId:'ubuntu-18-04-x64',
				osType: new OsType(code: 'ubuntu.18.04.64')
		)
		[virtualImage]
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		ComputeTypeLayout layout = this.context.getComputeTypeLayoutFactoryService().buildDockerLayout(
				'doplugin-docker',
				'18.04',
				this.code,
				DigitalOceanCloudProvider.LINUX_VIRTUAL_IMAGE_CODE,
				UBUNTU_VIRTUAL_IMAGE_CODE
		).blockingGet()

		[layout]
	}

	@Override
	public Collection<OptionType> getNodeOptionTypes() {
		return new ArrayList<OptionType>()
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		//image OptionType
		OptionType imageOption = new OptionType()
		imageOption.name = 'image'
		imageOption.code = 'digital-ocean-image'
		imageOption.fieldName = 'imageId'
		imageOption.fieldContext = 'config'
		imageOption.fieldLabel = 'Image'
		imageOption.inputType = OptionType.InputType.SELECT
		imageOption.displayOrder = 100
		imageOption.required = true
		imageOption.optionSource = 'pluginImage'
		[imageOption]
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		return []
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		return []
	}

	@Override
	String getCode() {
		return 'do-provider'
	}

	@Override
	String getName() {
		return 'DigitalOcean'
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return false
	}

	@Override
	Boolean hasPlanTagMatch() {
		false
	}

	@Override
	Integer getMaxNetworks() {
		return 1
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug "validateWorkload: ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		log.debug "validateInstance: ${instance} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		log.debug "validateDockerHost: ${server} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse.success()
	}

	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "DO Provision Provider: runWorkload ${workload.configs} ${opts}"
		def containerConfig = new groovy.json.JsonSlurper().parseText(workload.configs ?: '{}')
		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		if (!apiKey) {
			return new ServiceResponse(success: false, msg: 'No API Key provided')
		}

		Map callbackOpts = [:]
		ComputeServer server = workload.server
		VirtualImage virtualImage
		if(opts.cloneContainerId && opts.backupSetId) {
//			def snapshot = digitalOceanSnapshotBackupService.getSnapshotForBackupResult(opts.backupSetId, opts.cloneContainerId)
//			def snapshotImageId = snapshot?.snapshotId
//			if(snapshotImageId){
//				log.info("creating server from snapshot image: ${snapshotImageId}")
//				imageId = snapshotImageId
//			}
		} else {
			virtualImage = workload.server.sourceImage
		}

		
		// Grab the user configuration data (then update the server)
		UsersConfiguration usersConfiguration = morpheus.provision.getUserConfig(workload, virtualImage, opts).blockingGet()
		log.debug "usersConfiguration ${usersConfiguration}"
		server.sshUsername = usersConfiguration.sshUsername
		server.sshPassword = usersConfiguration.sshPassword
		morpheus.computeServer.save([server]).blockingGet()

		def userData
		if(virtualImage?.isCloudInit) {
			// Utilize the morpheus build cloud-init methods
			Map cloudConfigOptions = workloadRequest.cloudConfigOpts
			log.debug "cloudConfigOptions ${cloudConfigOptions}"

			// Inform Morpheus to install the agent (or not) after the server is created
			callbackOpts.installAgent = opts.installAgent && (cloudConfigOptions.installAgent != true)

			def cloudConfigUser = workloadRequest.cloudConfigUser
			log.debug "cloudConfigUser: ${cloudConfigUser}"
			userData = cloudConfigUser

			// Not really used in DO provisioning (example only)
			String metadata = workloadRequest.cloudConfigMeta
			log.debug "metadata: ${metadata}"

//			// Not really used in DO provisioning (example only)
//			String networkData = morpheus.provision.buildCloudNetworkData(com.morpheusdata.model.PlatformType.valueOf(server.osType), cloudConfigOptions).blockingGet()
//			log.debug "networkData: ${networkData}"
		} else {
			// These users will be created by Morpheus after provisioning
			callbackOpts.createUsers = usersConfiguration.createUsers
		}

		// Now.. ready to create it in DO
		def dropletConfig = [
			'name'              : workload.server.getExternalHostname(),
			'region'            : workload.server.cloud.configMap.datacenter,
			'size'              : workload.plan.externalId,
			'image'             : opts.imageRef,
			'backups'           : "${opts.doBackups}",
			'ipv6'              : false,
			'user_data'         : userData,
			'private_networking': workload.privateNetworking
		]
		dropletConfig.keys = morpheus.cloud.findOrGenerateKeyPair(workload.account).blockingGet().id

		def response = apiService.createDroplet(apiKey, dropletConfig)
		if (response.success) {
			log.debug "Droplet Created ${response.results}"

			// Need to store the link between the Morpheus ComputeServer reference and the Digital Ocean object
			def droplet = response.data
			def externalId = droplet.id
			server.externalId = externalId
			server.osDevice = '/dev/vda'
			server.dataDevice = '/dev/vda'
			server.lvmEnabled = false
			morpheus.computeServer.save([server]).blockingGet()

			return new ServiceResponse<WorkloadResponse>(success: true, data: new WorkloadResponse(externalId: externalId, installAgent: callbackOpts.installAgent, createUsers: callbackOpts.createUsers))
		} else {
			log.debug "Failed to create droplet: $response.results"
			return new ServiceResponse(success: false, msg: response.errorCode, content: response.content, error: response.data)
		}
	}

	@Override
	ServiceResponse prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.containerType) {
					ContainerType containerType = morpheus.containerType.get(computeTypeSet.containerType.id).blockingGet()
					virtualImage = containerType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error "${rtn.msg}, ${e}", e

		}
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse<HostResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "runHost: ${server} ${hostRequest} ${opts}"

		String apiKey = plugin.getAuthConfig(server.cloud).doApiKey
		if (!apiKey) {
			return new ServiceResponse(success: false, msg: 'No API Key provided')
		}
		
		// sshKeys needed?
//		opts.sshKeys = getKeyList(server.cloud, config.publicKeyId)

		def rtn = [success:false]
		def found = false
		log.info("findOrCreateServer:${server.externalId}")

		def dropletConfig = [
				'name'              : cleanInstanceName(server.name),
				'region'            : server.cloud.configMap.datacenter,
				'size'              : server.plan.externalId,
				'image'             : server.sourceImage.externalId,
				'backups'           : "${opts.doBackups}",
				'ipv6'              : false,
				'user_data'         : hostRequest.cloudConfigUser,
				'private_networking': false
		]
		log.debug "post body: $dropletConfig"
		def response = apiService.createDroplet(apiKey, dropletConfig)

		Map callbackOpts = [:]
		if(server.sourceImage?.isCloudInit) {
			// Utilize the morpheus built cloud-init methods
			Map cloudConfigOptions = hostRequest.cloudConfigOpts
			log.debug "cloudConfigOptions ${cloudConfigOptions}"

			// Inform Morpheus to install the agent (or not) after the server is created
			callbackOpts.installAgent = opts.installAgent && (cloudConfigOptions.installAgent != true)
		} else {
			// These users will be created by Morpheus after provisioning
			callbackOpts.createUsers = hostRequest.usersConfiguration
		}

		if (response.success) {
			log.debug "Droplet Created ${response.json}"

			// Need to store the link between the Morpheus ComputeServer reference and the Digital Ocean object
			def droplet = response.data
			def externalId = droplet.id
			server.externalId = externalId
			server.osDevice = '/dev/vda'
			server.dataDevice = '/dev/vda'
			server.lvmEnabled = false
			saveAndGet(server)

			return new ServiceResponse<HostResponse>(success: true, data: new HostResponse(externalId: externalId, installAgent: callbackOpts.installAgent, createUsers: callbackOpts.createUsers))
		} else {
			log.debug("Failed to create droplet: $response.results")
			return new ServiceResponse(success: false, msg: response?.errorCode, content: response.content, error: response.data)
		}
	}

	@Override
	ServiceResponse<HostResponse> waitForHost(ComputeServer server) {
		log.debug "waitForHost: ${server}"
		try {
			ServiceResponse<WorkloadResponse> statusResults = getServerDetails(server)
			WorkloadResponse workloadResponse = statusResults.data
			HostResponse hostResponse = new HostResponse([
					unattendCustomized: workloadResponse.unattendCustomized,
					externalId        : workloadResponse.externalId,
					publicIp          : workloadResponse.publicIp,
					privateIp         : workloadResponse.privateIp,
					installAgent      : workloadResponse.installAgent,
					noAgent           : workloadResponse.noAgent,
					createUsers       : workloadResponse.createUsers,
					success           : workloadResponse.success,
					customized        : workloadResponse.customized,
					licenseApplied    : workloadResponse.licenseApplied,
					poolId            : workloadResponse.poolId,
					hostname          : workloadResponse.hostname,
					message           : workloadResponse.message,
					skipNetworkWait   : workloadResponse.skipNetworkWait
			])
			return new ServiceResponse<HostResponse>(statusResults.success, statusResults.msg, statusResults.errors, hostResponse)
		} catch(e) {
			log.error "Error waitForHost: ${e}", e
			return new ServiceResponse(success: false, msg: "Error in waiting for Host: ${e}")
		}
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		log.debug "finalizeHost: ${server} "
		return ServiceResponse.success();
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeServer: ${server} ${resizeRequest} ${opts}"
		internalResizeServer(server, resizeRequest)
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug "resizeWorkload: ${instance} ${workload} ${resizeRequest} ${opts}"
		internalResizeServer(workload.server, resizeRequest)
	}

	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest) {
		log.debug "internalResizeServer: ${server} ${resizeRequest}"
		ServiceResponse rtn = ServiceResponse.success()
		try {
			String apiKey = plugin.getAuthConfig(server.cloud).doApiKey
			String dropletId = server.externalId
			Map actionConfig = [
					'disk': true,
					'size': resizeRequest.plan.externalId
			]
			rtn = apiService.performDropletAction(apiKey, dropletId, 'resize', actionConfig)
			if(rtn.success) {
				StorageVolume existingVolume = server.volumes?.getAt(0)
				if (existingVolume) {
					existingVolume.maxStorage = resizeRequest.maxStorage
					morpheus.storageVolume.save([existingVolume]).blockingGet()
				}
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = e.message
			log.error "Error in resizing server: ${e}", e
		}
		rtn
	}

	@Override
	ServiceResponse<WorkloadResponse> stopWorkload(Workload workload) {
		String dropletId = workload.server.externalId
		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		log.debug "stop server: ${dropletId}"
		if (!dropletId) {
			log.debug "no Droplet ID provided"
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}

		ServiceResponse response = apiService.performDropletAction(apiKey, dropletId, 'shutdown')
		if (response.success) {
			return apiService.checkActionComplete(apiKey, response.data.id)
		} else {
			powerOffServer(apiKey, dropletId)
		}
	}

	@Override
	ServiceResponse<WorkloadResponse> startWorkload(Workload workload) {
		String dropletId = workload.server.externalId
		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		log.debug "startWorkload for server: ${dropletId}"
		if (!dropletId) {
			log.debug "no Droplet ID provided"
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}
		return apiService.performDropletAction(apiKey, dropletId, 'power_on')
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug 'restartWorkload'
		ServiceResponse stopResult = stopWorkload(workload)
		if (stopResult.success) {
			rtn = startWorkload(workload)
		} else {
			rtn = stopResult
		}

		return rtn
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		String dropletId = workload.server.externalId
		log.debug "removeWorkload for server: ${dropletId}"
		if (!dropletId) {
			log.debug "no Droplet ID provided"
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}

		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		return apiService.deleteDroplet(apiKey, dropletId)
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.context
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		log.debug "getServerDetails"
		ServiceResponse rtn = ServiceResponse.prepare(new WorkloadResponse())
		String apiKey = plugin.getAuthConfig(server.cloud).doApiKey

		Boolean pending = true
		Integer attempts = 0
		while (pending) {
			log.debug "attempt $attempts"
			sleep(1000l * 20l)
			def resp = apiService.getDroplet(apiKey, server.externalId)
			if (resp.success) {
				if(resp.data?.status == "active") {
					rtn.success = true
					rtn.data = dropletToWorkloadResponse(resp.data)
					pending = false
				} else if(resp.msg == 'failed') {
					rtn.msg = resp.msg
					pending = false
				}
			}
			attempts++
			if (attempts > 15) {
				pending = false
			}
		}

		rtn
	}

	ServiceResponse<WorkloadResponse> powerOffServer(String apiKey, String dropletId) {
		log.debug "power off server"
		return apiService.performDropletAction(apiKey, dropletId, 'power_off')
	}

	protected WorkloadResponse dropletToWorkloadResponse(droplet) {
		WorkloadResponse workloadResponse = new WorkloadResponse()
		workloadResponse.externalId = droplet?.id
		def publicNetwork = droplet?.networks?.v4?.find {
			it.type == 'public'
		}
		def privateNetwork = droplet?.networks?.v4?.find {
			it.type == 'private'
		}
		def publicIp = publicNetwork?.ip_address
		def privateIp = privateNetwork?.ip_address ?: publicIp
		workloadResponse.publicIp = publicIp
		workloadResponse.privateIp = privateIp
		workloadResponse
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		morpheus.computeServer.save([server]).blockingGet()
		return morpheus.computeServer.get(server.id).blockingGet()
	}

	protected cleanInstanceName(name) {
		def rtn = name.replaceAll(/[^a-zA-Z0-9\.\-]/,'')
		return rtn
	}
}