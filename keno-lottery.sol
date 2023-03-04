//SPDX-License-Identifier: GPL-3.0
 
pragma solidity >=0.5.0 <0.9.0;
 
 
/* Keno is a lottery-like gambling game often played at modern casinos. 
The game originated in China. Legend has it that the invention of the game saved an ancient city
in time of war and its widespread popularity helped raise funds to build the Great Wall of China.

Lottery algorithm:
1. The lottery starts by accepting ETH transactions. Anyone having an Ethereum wallet can send amount
of 0.1 ETH directly to the contract's address.
2. The players send ETH directly to the contract address and theirEthereum address is registered. 
A user can send more transactions having more chaces to win.
3. There is a manager, the account that deploys and controls the contract.
4. At some point, if there are at least 3 players, he can pick a random winner from the players list.
Only the manager is allowed to see the contract balance and to randomly select the winner.
5. The contract will transfer the entire balance to the winner's address and the lottery is reset
and ready for the next round
*/

contract Lottery{
    
    // declaring the state variables
    address payable[] public players; //dynamic array of type address payable
    address public manager; 
    
    
    // declaring the constructor
    constructor(){
        // initializing the owner to the address that deploys the contract
        manager = msg.sender; 
    }
    
    // declaring the receive() function that is necessary to receive ETH
    receive () payable external{
        // each player sends exactly 0.1 ETH 
        require(msg.value == 0.1 ether);
        // appending the player to the players array
        players.push(payable(msg.sender));
    }
    
    // returning the contract's balance in wei
    function getBalance() public view returns(uint){
        // only the manager is allowed to call it
        require(msg.sender == manager);
        return address(this).balance;
    }
    
    // helper function that returns a big random integer
    function random() internal view returns(uint){
       return uint(keccak256(abi.encodePacked(block.difficulty, block.timestamp, players.length)));
    }
    
    
    // selecting the winner
    function pickWinner() public{
        // only the manager can pick a winner if there are at least 3 players in the lottery
        require(msg.sender == manager);
        require (players.length >= 3);
        
        uint r = random();
        address payable winner;
        
        // computing a random index of the array
        uint index = r % players.length;
    
        winner = players[index]; // this is the winner
        
        // transferring the entire contract's balance to the winner
        winner.transfer(getBalance());
        
        // resetting the lottery for the next round
        players = new address payable[](0);
    }
 
}
